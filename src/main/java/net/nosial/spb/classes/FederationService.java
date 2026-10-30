package net.nosial.spb.classes;

import net.nosial.jfederation.FederationClient;
import net.nosial.jfederation.enums.ClassificationFlag;
import net.nosial.jfederation.enums.EntityRelationshipType;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.jfederation.exceptions.FederationClientException;
import net.nosial.jfederation.records.BlacklistRecord;
import net.nosial.jfederation.records.ContentInput;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.jfederation.records.EntityRecord;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.FileAttachmentRecord;
import net.nosial.jfederation.records.OperatorRecord;
import net.nosial.jfederation.records.OperatorConfiguration;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.jfederation.records.ReportSubmission;
import net.nosial.jfederation.records.ScanFile;
import net.nosial.jfederation.records.ScannedContent;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.exceptions.FederationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Everything the bot asks of a Federation server.
 *
 * <p>Handlers talk to this rather than to the vendor client, for three reasons. It names the
 * fifteen operations the bot actually performs out of the client's ~180, so the dependency is
 * legible. It turns the client's unchecked failures into one checked {@link FederationException},
 * so a handler cannot forget that the network is involved. And a single {@link #unavailable()}
 * instance stands in for handler tests, so they never need a live server.
 *
 * <p>Federation is optional: when the configuration has no {@code federation} section the bot runs
 * with {@link #unavailable()}, which reports itself {@linkplain #isAvailable() unavailable} and the
 * features that need it say so rather than failing. Every caller must check {@link #isAvailable()}
 * first, or be prepared for {@link FederationException}.
 *
 * <p>A server accepts an anonymous client for whatever it has marked public, but every write and
 * some reads require an authenticated one — and, server-side, that bar is not "a token was sent"
 * but {@link OperatorRecord#hasClientPermissions() client permissions}: a token that identifies a
 * disabled operator or one with no client permissions gives the bot nothing it would not have
 * anonymously. {@link #authenticate()} is how the bot finds out which side of that line its own token
 * falls on, and {@link #isAuthenticated()} is what callers that only make sense as the bot itself —
 * publishing an entity, submitting a report as the bot rather than an operator — check first, so
 * those features are skipped rather than attempted and rejected. This is unrelated to an
 * operator's own {@code /auth} session, which carries its own access token through each call and is
 * unaffected by the bot's own authentication state.
 *
 * <p>Only a definitive answer about the token settles it: a token the server rejects, or one that
 * identifies an operator without client permissions, is discarded and the service carries on as an
 * anonymous client. A server that is unreachable or failing says nothing about the token, so it is
 * kept and authentication stays {@linkplain #isAuthenticationPending() pending} until a later
 * {@link #authenticate()} gets an answer.
 *
 * <p>Instances are shared between worker threads and are thread-safe.
 */
public final class FederationService implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FederationService.class);
    private static final int BLACKLIST_PAGE_SIZE = 5;
    public static final FederationService UNAVAILABLE = new FederationService();

    private final Cache<String, ServerInformation> scanCapabilities = Cache.create(1, 5, TimeUnit.MINUTES);

    private final String endpoint;
    private volatile String accessToken;
    private volatile FederationClient client;
    private volatile boolean authenticated;
    // Requests are held back until this instant after the server rejected one with HTTP 429 or 503 (OFD 2.2 "Rate
    // Limiting"), guarded by this.rateLimitLock
    private final Object rateLimitLock = new Object();
    private Instant retryNotBefore = Instant.EPOCH;
    private int consecutiveRejections;

    /** Creates the unavailable instance; see {@link #UNAVAILABLE}. */
    private FederationService()
    {
        this.endpoint = null;
        this.accessToken = null;
        this.client = null;
    }

    /**
     * Creates the service over a Federation server.
     *
     * @param endpoint the base URL of the server
     * @param accessToken the bot's own access token, or {@code null} for anonymous access
     */
    public FederationService(String endpoint, String accessToken)
    {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.accessToken = accessToken;
        this.client = new FederationClient(endpoint, accessToken);
    }

    /**
     * Returns the shared instance used when the configuration has no {@code federation} section.
     *
     * <p>The bot is designed to run without Federation: scanning, reporting, and blacklisting simply
     * announce themselves as unavailable while moderation, configuration, and localization keep
     * working. Having an instance that says so is what lets every handler write
     * {@code context.federation().isAvailable()} instead of a null check, and turns a forgotten check
     * into a clear message rather than a {@link NullPointerException}.
     *
     * @return the shared unavailable instance
     */
    public static FederationService unavailable()
    {
        return UNAVAILABLE;
    }

    /**
     * Returns whether a Federation server is configured and can be called at all.
     *
     * <p>This reports configuration, not reachability: a configured server that is currently down
     * is still "available" and its calls fail with {@link FederationException}.
     *
     * @return {@code true} when Federation is configured
     */
    public boolean isAvailable()
    {
        return this.client != null;
    }

    /**
     * Returns whether the bot's own access token carries client permissions, as of the last
     * {@link #authenticate()} call.
     *
     * <p>This is {@code false} until {@link #authenticate()} has run, for an anonymous client (no
     * token configured), and for a token the server accepted but did not grant client permissions
     * to. Callers that act as the bot itself for a write the server never allows anonymously —
     * {@link #publishEntity}, or {@link #submitReport}/{@link #uploadAttachment} with a {@code null}
     * access token — check this first so the attempt is skipped rather than made and rejected. It
     * says nothing about an operator's own {@code /auth} session, which is unaffected either way.
     *
     * @return {@code true} when the bot's own token grants client permissions
     */
    public boolean isAuthenticated()
    {
        return this.authenticated;
    }

    /**
     * Returns whether the bot has an access token whose standing the server has not yet settled,
     * so {@link #authenticate()} is worth calling again.
     *
     * <p>This is the case before the first {@link #authenticate()} and after any attempt that failed
     * without the server answering for the token, typically because it was unreachable. It is
     * {@code false} once the token is known to grant client permissions, once it has been discarded,
     * and when no token was configured.
     *
     * @return {@code true} when authentication should be retried
     */
    public boolean isAuthenticationPending()
    {
        String token = this.accessToken;
        return this.client != null && token != null && !token.isBlank() && !this.authenticated;
    }

    /**
     * Identifies the operator the bot's own access token belongs to and records whether it grants
     * client permissions, for later {@link #isAuthenticated()} checks.
     *
     * <p>A token the server rejects or that lacks client permissions is discarded, and every later
     * call is made anonymously. Any other failure leaves the token in place and authentication
     * {@linkplain #isAuthenticationPending() pending}, so a server that was down when this was
     * called can be authenticated against once it is back.
     *
     * @return the identified operator, or {@code null} when Federation is not configured or there is
     *         no access token (an anonymous client has nothing to identify)
     * @throws FederationException If the server could not be reached or rejected the token
     */
    public synchronized OperatorRecord authenticate() throws FederationException
    {
        if (!isAuthenticationPending() && !this.authenticated)
        {
            return null;
        }

        OperatorRecord operator;
        try
        {
            operator = self();
        }
        catch (FederationException e)
        {
            if (isCredentialRejected(e))
            {
                discardAccessToken();
            }
            throw e;
        }

        this.authenticated = false;
        if (operator.disabled() || !operator.hasClientPermissions())
        {
            discardAccessToken();
            return operator;
        }

        if (!operator.isBot())
        {
            if ("root".equalsIgnoreCase(operator.name()) || "system".equalsIgnoreCase(operator.name()))
            {
                throw new FederationException("SpamProtectionBot requires a dedicated Federation operator with client permissions; root and system cannot be configured as bots");
            }
            String uuid = operator.uuid();
            OperatorConfiguration configuration = call("mark the Federation operator as a bot",
                    () -> this.client.updateOperatorConfiguration(uuid, null, null, true));
            if (configuration == null || !configuration.isBot())
            {
                throw new FederationException("Federation did not confirm the bot operator configuration");
            }
            operator = new OperatorRecord(operator.uuid(), operator.name(), operator.disabled(),
                    operator.permissions(), configuration, operator.created(), operator.updated());
        }
        this.authenticated = true;
        return operator;
    }

    /**
     * Replaces the client with an anonymous one, since a token without client permissions grants the
     * bot nothing its own features need, and settles authentication so it is not retried.
     */
    private void discardAccessToken()
    {
        FederationClient previous = this.client;
        this.client = new FederationClient(this.endpoint, null);
        this.accessToken = null;
        this.authenticated = false;
        previous.close();
    }

    /**
     * Returns the base URL of the configured server.
     *
     * @return the endpoint, or {@code null} when Federation is not configured
     */
    public String endpoint()
    {
        return this.endpoint;
    }

    /**
     * Returns the host of the configured server, for display.
     *
     * @return the host, or {@code null} when Federation is not configured
     */
    public String host()
    {
        if (this.endpoint == null)
        {
            return null;
        }

        try
        {
            String host = new URI(this.endpoint).getHost();
            return host != null ? host : this.endpoint;
        }
        catch (Exception e)
        {
            return this.endpoint;
        }
    }

    /**
     * Returns the server's name, version, and record counts.
     *
     * @return the server information
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ServerInformation serverInformation() throws FederationException
    {
        requireAvailable();

        return call("read server information", this.client::getServerInformation);
    }

    /**
     * Returns the operator the bot's own access token belongs to.
     *
     * @return the operator record
     * @throws FederationException If the server is unavailable or the token was rejected
     */
    public OperatorRecord self() throws FederationException
    {
        requireAvailable();

        return call("read the bot's own operator record", this.client::getSelf);
    }

    /**
     * Returns the operator a given access token belongs to, without changing the bot's own
     * credentials.
     *
     * @param accessToken the token to identify
     * @return the operator record
     * @throws FederationException If the server is unavailable or the token was rejected
     */
    public OperatorRecord operatorFor(String accessToken) throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(accessToken, "accessToken must not be null");

        return asOperator(accessToken, "identify an operator", FederationClient::getSelf);
    }

    /**
     * Returns the Federation record for an entity.
     *
     * @param entity the entity address, UUID, or hash
     * @return the record, or {@link Optional#empty()} when nothing matches
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<EntityRecord> entity(String entity) throws FederationException
    {
        requireAvailable();

        if (entity == null || entity.isBlank())
        {
            return Optional.empty();
        }

        return optional("look up entity " + entity, () -> this.client.getEntityRecord(entity));
    }

    /**
     * Asks the server what it recommends doing about an entity.
     *
     * <p>Unlike {@link #entity(String)}, which returns what is stored, this returns the server's
     * assessment: the suggested action, when it lifts, and the records behind it. It is what join
     * protection and scanning act on.
     *
     * @param identifier the entity address, UUID, or hash
     * @return the assessment, or {@link Optional#empty()} when the server knows nothing
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<EntityQueryResult> queryEntity(String identifier) throws FederationException
    {
        requireAvailable();

        if (identifier == null || identifier.isBlank())
        {
            return Optional.empty();
        }

        return optional("query entity " + identifier, () -> this.client.queryEntity(identifier));
    }

    /**
     * Registers an entity with the server, returning its address.
     *
     * @param host the host the identifier belongs to, e.g. {@code telegram.org}, or for a host
     *             entity the canonical domain name or IP address itself
     * @param identifier the identifier within that host, e.g. a Telegram user id, or {@code null}
     *                   for a host entity
     * @param metadata additional attributes to publish, or {@code null} for none
     * @return the entity address assigned by the server
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public String publishEntity(String host, String identifier, Map<String, Object> metadata) throws FederationException
    {
        requireAvailable();
        requireClientPermissions("publish an entity");
        Objects.requireNonNull(host, "host must not be null");

        String address = identifier != null ? identifier + "@" + host : host;
        return call("publish entity " + address, () -> this.client.pushEntity(host, identifier, metadata));
    }

    /**
     * Records a relationship between two entities.
     *
     * @param accessToken the operator's access token, or {@code null} to act as the bot
     * @param entity the source entity
     * @param target the target entity
     * @param type the relationship type
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public void linkEntities(String accessToken, String entity, String target, EntityRelationshipType type)
            throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(type, "type must not be null");

        String what = "link " + entity + " to " + target;
        if (accessToken == null)
        {
            call(what, () ->
            {
                this.client.setEntityRelationship(entity, target, type);
                return null;
            });
            return;
        }

        asOperator(accessToken, what, operator ->
        {
            operator.setEntityRelationship(entity, target, type);
            return null;
        });
    }

    /**
     * Scans a piece of content for spam, phishing, malware, and other threats.
     *
     * @param content the content to scan, with whatever metadata the caller chose to attach
     * @param entity the entity the content came from, or {@code null} to scan anonymously
     * @return the scan result and its suggested action
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ScannedContent scanContent(ContentInput content, String entity) throws FederationException
    {
        return scanContent(content, entity, List.of());
    }

    /**
     * Scans a piece of content together with its files, such as the images of a message. Every server accepts
     * files, a server that cannot interpret them, or does not scan them for the bot, ignores them.
     *
     * @param content the content to scan, which may have no text when files are given
     * @param entity the entity the content came from, or {@code null} to scan anonymously
     * @param files the files of the content, empty for none
     * @return the scan result and its suggested action
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ScannedContent scanContent(ContentInput content, String entity, List<ScanFile> files) throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(content, "content must not be null");

        return call("scan content", () -> this.client.scanContent(content, entity, files));
    }

    /**
     * Returns the server information that scanning depends on, its concerns and file limits. It is cached briefly
     * to avoid an information request for every message.
     *
     * @return the server information
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ServerInformation scanInformation() throws FederationException
    {
        return call("read scan capabilities",
                () -> this.scanCapabilities.get("info", ignored -> this.client.getServerInformation()));
    }

    /**
     * Scans content with the server's usual concerns, optionally adding ads and trackers.
     * Only advertised concerns are added, so servers without these optional types still scan
     * normally. Capabilities are cached briefly to avoid an information request for every message.
     *
     * @param content the content to scan
     * @param entity the author entity, or {@code null} to scan anonymously
     * @param blockAdsTrackers whether to include advertising and tracking concerns
     * @return the scan result and its suggested action
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ScannedContent scanContent(ContentInput content, String entity, boolean blockAdsTrackers) throws FederationException
    {
        return scanContent(content, entity, blockAdsTrackers, List.of());
    }

    /**
     * Scans content and its files with the server's usual concerns, optionally adding ads and trackers.
     *
     * @param content the content to scan, which may have no text when files are given
     * @param entity the author entity, or {@code null} to scan anonymously
     * @param blockAdsTrackers whether to include advertising and tracking concerns
     * @param files the files of the content, empty for none
     * @return the scan result and its suggested action
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ScannedContent scanContent(ContentInput content, String entity, boolean blockAdsTrackers, List<ScanFile> files)
            throws FederationException
    {
        if (!blockAdsTrackers)
        {
            return scanContent(content, entity, files);
        }
        requireAvailable();
        Objects.requireNonNull(content, "content must not be null");

        ServerInformation information;
        try
        {
            information = scanInformation();
        }
        catch (FederationException e)
        {
            LOGGER.warn("Unable to read advertising and tracking scan capabilities; scanning with server defaults: {}",
                    e.getMessage());
            return scanContent(content, entity, files);
        }

        return call("scan content", () ->
        {
            List<IncidentType> concerns = new ArrayList<>(information.defaultConcerns());
            for (IncidentType concern : List.of(IncidentType.ADVERTISING, IncidentType.TRACKING))
            {
                if (information.concerns().contains(concern) && !concerns.contains(concern))
                {
                    concerns.add(concern);
                }
            }
            return this.client.scanContent(List.of(content), entity, null, null, concerns.isEmpty() ? null : concerns,
                    files == null || files.isEmpty() ? null : Map.of(0, files));
        });
    }

    /**
     * Returns the blacklist records currently active against an entity.
     *
     * @param entity the entity address or UUID
     * @return the active records, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public List<BlacklistRecord> activeBlacklists(String entity) throws FederationException
    {
        requireAvailable();

        return list("list blacklists for " + entity,
                () -> this.client.listEntityBlacklistRecords(entity, 1, BLACKLIST_PAGE_SIZE, false));
    }

    /**
     * Blacklists an entity, optionally referencing an existing report.
     *
     * @param accessToken the operator's access token, or {@code null} to act as the bot
     * @param entity the entity to blacklist
     * @param report the UUID of the supporting report, or {@code null} for none
     * @param type the incident type
     * @param expiresInSeconds expiration Unix timestamp in seconds, or {@code null} for permanent
     * @return the UUID of the created blacklist record
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public String blacklistEntity(String accessToken, String entity, String report, IncidentType type,
                                  Integer expiresInSeconds) throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(type, "type must not be null");

        String what = "blacklist " + entity;
        return accessToken == null
                ? call(what, () -> this.client.blacklistEntity(entity, report, type, expiresInSeconds))
                : asOperator(accessToken, what,
                        operator -> operator.blacklistEntity(entity, report, type, expiresInSeconds));
    }

    /**
     * Returns a report by its UUID.
     *
     * @param uuid the report UUID
     * @return the report, or {@link Optional#empty()} when nothing matches
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<ReportRecord> report(String uuid) throws FederationException
    {
        requireAvailable();

        if (uuid == null || uuid.isBlank())
        {
            return Optional.empty();
        }

        return optional("look up report " + uuid, () -> this.client.getReport(uuid));
    }

    /**
     * Returns a report by its UUID as seen by an operator, who may see reports the bot's own
     * client cannot.
     *
     * @param accessToken the operator's access token
     * @param uuid the report UUID
     * @return the report, or {@link Optional#empty()} when nothing matches
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<ReportRecord> report(String accessToken, String uuid) throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(accessToken, "accessToken must not be null");

        if (uuid == null || uuid.isBlank())
        {
            return Optional.empty();
        }

        String what = "look up report " + uuid;
        try (FederationClient operator = new FederationClient(this.endpoint, accessToken))
        {
            return optional(what, () -> operator.getReport(uuid));
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the reports filed against an entity.
     *
     * @param entity the entity address or UUID
     * @param limit the maximum number of reports
     * @return the reports, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public List<ReportRecord> entityReports(String entity, int limit) throws FederationException
    {
        requireAvailable();

        return list("list reports for " + entity, () -> this.client.listEntityReports(entity, 1, limit));
    }

    /**
     * Returns the reports that are open and assigned to the given operator.
     *
     * @param accessToken the operator's access token
     * @param limit the maximum number of reports
     * @return the open assigned reports, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public List<ReportRecord> openAssignedReports(String accessToken, int limit) throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(accessToken, "accessToken must not be null");

        List<ReportRecord> reports = asOperator(accessToken, "list open reports", operator -> operator.listOpenedReports(1, limit, "CREATED", "ASC"));
        return reports != null ? reports : List.of();
    }

    /**
     * Submits a report.
     *
     * @param accessToken the operator's access token, or {@code null} to submit as the bot
     * @param entity the entity being reported
     * @param evidence the reported content and its tag
     * @param type the incident type
     * @param message the reporter's comment, or {@code null}
     * @return the server's acknowledgement, carrying the new report UUID
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public ReportSubmission submitReport(String accessToken, String entity, ContentInput evidence, IncidentType type, String message)
            throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(type, "type must not be null");

        String what = "submit a report against " + entity;
        if (accessToken == null)
        {
            requireClientPermissions(what);
            return call(what, () -> this.client.submitReport(entity, evidence, type, message));
        }
        return asOperator(accessToken, what, operator -> operator.submitReport(entity, evidence, type, message));
    }

    /**
     * Closes a report with a classification.
     *
     * @param accessToken the access token of the operator closing the report
     * @param uuid the report UUID
     * @param classification the classification to close with, or {@code null} for none
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public void closeReport(String accessToken, String uuid, ClassificationFlag classification)
            throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(uuid, "uuid must not be null");

        asOperator(accessToken, "close report " + uuid, operator ->
        {
            if (classification == null)
            {
                operator.closeReport(uuid);
            }
            else
            {
                operator.closeReport(uuid, classification);
            }
            return null;
        });
    }

    /**
     * Returns an evidence record by its UUID.
     *
     * @param uuid the evidence UUID
     * @return the record, or {@link Optional#empty()} when nothing matches
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<EvidenceRecord> evidence(String uuid) throws FederationException
    {
        requireAvailable();

        if (uuid == null || uuid.isBlank())
        {
            return Optional.empty();
        }

        return optional("look up evidence " + uuid, () -> this.client.getEvidenceRecord(uuid));
    }

    /**
     * Returns an evidence record, read as a particular operator.
     *
     * <p>Confidential evidence is only visible to the operator it belongs to, so a lookup made on
     * behalf of an authenticated operator must carry their token rather than the bot's.
     *
     * @param accessToken the operator's access token, or {@code null} to read as the bot
     * @param uuid the evidence UUID
     * @return the record, or {@link Optional#empty()} when nothing matches
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public Optional<EvidenceRecord> evidenceAs(String accessToken, String uuid) throws FederationException
    {
        requireAvailable();

        if (uuid == null || uuid.isBlank())
        {
            return Optional.empty();
        }

        if (accessToken == null)
        {
            return evidence(uuid);
        }

        try
        {
            return Optional.ofNullable(asOperator(accessToken, "look up evidence " + uuid, operator -> operator.getEvidenceRecord(uuid)));
        }
        catch (FederationException e)
        {
            if (e.getCause() instanceof FederationClientException cause && isNotFound(cause))
            {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Returns the files attached to an evidence record.
     *
     * @param accessToken the operator's access token, or {@code null} to read as the bot
     * @param evidenceUuid the evidence UUID
     * @return the attachment records, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public List<FileAttachmentRecord> evidenceAttachments(String accessToken, String evidenceUuid)
            throws FederationException
    {
        requireAvailable();

        String what = "list attachments for evidence " + evidenceUuid;
        List<FileAttachmentRecord> attachments = accessToken == null
                ? call(what, () -> this.client.getEvidenceAttachments(evidenceUuid))
                : asOperator(accessToken, what, operator -> operator.getEvidenceAttachments(evidenceUuid));
        return attachments != null ? attachments : List.of();
    }

    /**
     * Downloads an attachment into a local directory.
     *
     * @param accessToken the operator's access token, or {@code null} to read as the bot
     * @param attachmentUuid the attachment UUID
     * @param destinationDirectory the directory to write the file into
     * @return the path of the downloaded file, or {@code null} when the server sent nothing
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public String downloadAttachment(String accessToken, String attachmentUuid, String destinationDirectory)
            throws FederationException
    {
        requireAvailable();

        String what = "download attachment " + attachmentUuid;
        return accessToken == null
                ? call(what, () -> this.client.downloadAttachment(attachmentUuid, destinationDirectory))
                : asOperator(accessToken, what,
                        operator -> operator.downloadAttachment(attachmentUuid, destinationDirectory));
    }

    /**
     * Returns the evidence attached to a report.
     *
     * @param uuid the report UUID
     * @param limit the maximum number of records
     * @return the evidence records, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public List<EvidenceRecord> reportEvidence(String uuid, int limit) throws FederationException
    {
        requireAvailable();

        return list("list evidence for report " + uuid, () -> this.client.listReportEvidenceRecords(uuid, 1, limit));
    }

    /**
     * Uploads a file as an attachment to an evidence record.
     *
     * @param accessToken the operator's access token, or {@code null} to upload as the bot
     * @param evidenceUuid the evidence the file belongs to
     * @param filePath the local path of the file to upload
     * @param fileName the name to store the file under
     * @return the UUID of the stored attachment
     * @throws FederationException If the server is unavailable or rejected the call
     */
    public String uploadAttachment(String accessToken, String evidenceUuid, String filePath, String fileName)
            throws FederationException
    {
        requireAvailable();
        Objects.requireNonNull(evidenceUuid, "evidenceUuid must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");

        String what = "upload an attachment to evidence " + evidenceUuid;
        if (accessToken == null)
        {
            requireClientPermissions(what);
            return call(what, () -> this.client.uploadFileAttachment(evidenceUuid, filePath, fileName).uuid());
        }
        return asOperator(accessToken, what, operator -> operator.uploadFileAttachment(evidenceUuid, filePath, fileName).uuid());
    }

    /**
     * Releases whatever the instance holds. Safe to call more than once, and harmless on the
     * {@linkplain #unavailable() unavailable instance}.
     */
    @Override
    public void close()
    {
        if (this.client == null)
        {
            return;
        }

        try
        {
            this.client.close();
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Failed to close the Federation client cleanly", e);
        }
    }

    /**
     * Fails fast with the "not configured" message when this is the unavailable instance.
     *
     * @throws FederationException when Federation is not configured
     */
    private void requireAvailable() throws FederationException
    {
        if (this.client == null)
        {
            throw new FederationException("Federation is not configured");
        }
    }

    /**
     * Fails fast, without a network round trip, when the bot's own token does not carry client
     * permissions. The server rejects these calls unconditionally when acting as the bot — there is
     * no anonymous path for them — so there is nothing to gain from attempting one first.
     *
     * @param what a description of the call, used in the failure message
     * @throws FederationException when {@link #isAuthenticated()} is {@code false}
     */
    private void requireClientPermissions(String what) throws FederationException
    {
        if (!this.authenticated)
        {
            throw new FederationException("Failed to " + what + ": This action requires the bot's own Federation access token to carry client permissions");
        }
    }

    /**
     * Runs a call against the shared client, translating vendor failures.
     *
     * @param what a description of the call, used in the failure message
     * @param call the call to run
     * @param <T> the result type
     * @return the call's result
     * @throws FederationException If the server is unavailable or rejected the call
     */
    private <T> T call(String what, Supplier<T> call) throws FederationException
    {
        requireAvailable();
        requireNotRateLimited(what);

        try
        {
            T result = call.get();
            recordAccepted();
            return result;
        }
        catch (FederationClientException e)
        {
            recordRejected(e);
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fails fast, without a network round trip, while the server asked the bot to wait before
     * sending further requests (OFD 2.2 "Rate Limiting"). Its limits are not published and may
     * cover every method, so every request of the bot's own client waits.
     *
     * @param what a description of the call, used in the failure message
     * @throws FederationException while the delay of the last rejection has not elapsed
     */
    private void requireNotRateLimited(String what) throws FederationException
    {
        Duration remaining;
        synchronized (this.rateLimitLock)
        {
            remaining = Duration.between(Instant.now(), this.retryNotBefore);
        }

        if (!remaining.isNegative() && !remaining.isZero())
        {
            throw new FederationException("Failed to " + what + ": The Federation server is limiting requests, retrying in "
                    + Math.max(1, remaining.toSeconds()) + " second(s)");
        }
    }

    /**
     * Holds back further requests after the server rejected one with HTTP 429 or 503, for the
     * delay it gave, or, when it gave none (as servers before OFD 2.2 do), for a wait that doubles
     * with each consecutive rejection, from one second up to five minutes.
     *
     * @param e the failure reported by the client
     */
    private void recordRejected(FederationClientException e)
    {
        if (!e.isRetryable())
        {
            return;
        }

        Duration delay = e.getRetryAfter();
        synchronized (this.rateLimitLock)
        {
            if (delay == null)
            {
                delay = Duration.ofSeconds(Math.min(300L, 1L << Math.min(this.consecutiveRejections, 9)));
            }

            this.consecutiveRejections++;
            this.retryNotBefore = Instant.now().plus(delay);
        }

        LOGGER.warn("Federation server rejected a request with HTTP {}; holding back requests for {} second(s)",
                e.getStatusCode(), delay.toSeconds());
    }

    /**
     * Resets the wait of {@link #recordRejected(FederationClientException)} once the server
     * accepts a request again.
     */
    private void recordAccepted()
    {
        synchronized (this.rateLimitLock)
        {
            this.consecutiveRejections = 0;
        }
    }

    /**
     * Runs a lookup that may legitimately find nothing.
     *
     * @param what a description of the call, used in the failure message
     * @param call the lookup to run
     * @param <T> the record type
     * @return the record, or {@link Optional#empty()} when the server has none
     * @throws FederationException If the server is unavailable or rejected the call
     */
    private <T> Optional<T> optional(String what, Supplier<T> call) throws FederationException
    {
        requireNotRateLimited(what);

        try
        {
            Optional<T> result = Optional.ofNullable(call.get());
            recordAccepted();
            return result;
        }
        catch (FederationClientException e)
        {
            if (isNotFound(e))
            {
                recordAccepted();
                return Optional.empty();
            }
            recordRejected(e);
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs a listing, never returning {@code null}.
     *
     * @param what a description of the call, used in the failure message
     * @param call the listing to run
     * @param <T> the record type
     * @return the records, never {@code null}
     * @throws FederationException If the server is unavailable or rejected the call
     */
    private <T> List<T> list(String what, Supplier<List<T>> call) throws FederationException
    {
        List<T> result = call(what, call);
        return result != null ? result : List.of();
    }

    /**
     * Runs a call as a particular operator, on a client bound to that operator's token.
     *
     * @param accessToken the operator's access token
     * @param what a description of the call, used in the failure message
     * @param call the call to run
     * @param <T> the result type
     * @return the call's result
     * @throws FederationException If the server is unavailable or rejected the call
     */
    private <T> T asOperator(String accessToken, String what, Function<FederationClient, T> call) throws FederationException
    {
        try (FederationClient operator = new FederationClient(this.endpoint, accessToken))
        {
            return call.apply(operator);
        }
        catch (FederationClientException | IllegalArgumentException | IllegalStateException e)
        {
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns whether a failure means the server rejected the access token itself, as opposed to
     * being unreachable or failing for some other reason.
     *
     * <p>The server answers {@code 401} for a token it does not recognise and {@code 403} for one
     * belonging to a disabled operator. Either way, retrying with the same token cannot succeed, so
     * a caller holding a stored operator credential should discard it. A failure with no HTTP
     * response (status {@code 0}) or any other status says nothing about the token.
     *
     * @param e the failure reported by an operator call
     * @return {@code true} when the access token was rejected
     */
    public static boolean isCredentialRejected(FederationException e)
    {
        if (!(e.getCause() instanceof FederationClientException cause))
        {
            return false;
        }

        int status = cause.getStatusCode();
        return status == 401 || status == 403;
    }

    /**
     * Returns whether a failure means "the server has no such record" rather than a real error.
     *
     * @param e the failure reported by the client
     * @return {@code true} when the record simply does not exist
     */
    private static boolean isNotFound(FederationClientException e)
    {
        return e.getStatusCode() == 404;
    }
}
