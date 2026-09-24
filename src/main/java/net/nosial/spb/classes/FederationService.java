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
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.jfederation.records.ReportSubmission;
import net.nosial.jfederation.records.ScannedContent;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.exceptions.FederationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * but {@link OperatorRecord#clientPermissions() client permissions}: a token that identifies a
 * disabled operator or one with no client permissions is, for every practical purpose, worse than
 * no token at all, since the server holds it to the authenticated standard without it clearing that
 * standard. {@link #authenticate()} is how the bot finds out which side of that line its own token
 * falls on, and {@link #isAuthenticated()} is what callers that only make sense as the bot itself —
 * publishing an entity, submitting a report as the bot rather than an operator — check first, so
 * those features are skipped rather than attempted and rejected. This is unrelated to an
 * operator's own {@code /auth} session, which carries its own access token through each call and is
 * unaffected by the bot's own authentication state.
 *
 * <p>Instances are shared between worker threads and are thread-safe.
 */
public final class FederationService implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FederationService.class);

    /** The single instance used when no Federation server is configured; it holds no state. */
    public static final FederationService UNAVAILABLE = new FederationService();

    /** How many blacklist records are read for an entity before the rest are ignored. */
    private static final int BLACKLIST_PAGE_SIZE = 5;

    private final String endpoint;
    private final String accessToken;
    private final FederationClient client;
    private volatile boolean authenticated;

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
     * Identifies the operator the bot's own access token belongs to and records whether it grants
     * client permissions, for later {@link #isAuthenticated()} checks.
     *
     * <p>Meant to be called once, right after construction: the identity a token resolves to does
     * not change over the process lifetime, so there is nothing to gain from re-checking per call
     * the way {@link #isAvailable()} deliberately does not cache reachability.
     *
     * @return the identified operator, or {@code null} when Federation is not configured or no
     *         access token was given (an anonymous client has nothing to identify)
     * @throws FederationException If a token was configured but the server rejected it
     */
    public OperatorRecord authenticate() throws FederationException
    {
        if (this.client == null || this.accessToken == null || this.accessToken.isBlank())
        {
            return null;
        }

        OperatorRecord operator = self();
        this.authenticated = operator.clientPermissions() || operator.managementPermissions();
        return operator;
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
        requireAvailable();
        Objects.requireNonNull(content, "content must not be null");

        return call("scan content", () -> this.client.scanContent(content, entity));
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
     * Blacklists an entity against an existing report.
     *
     * @param accessToken the operator's access token, or {@code null} to act as the bot
     * @param entity the entity to blacklist
     * @param report the UUID of the supporting report
     * @param type the incident type
     * @param expiresInSeconds how long the blacklist lasts, or {@code null} for permanent
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

        List<ReportRecord> reports = asOperator(accessToken, "list open reports", operator -> operator.listOpenedReports(1, limit, "created", "ASC"));
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

        try
        {
            return call.get();
        }
        catch (FederationClientException | IllegalArgumentException | IllegalStateException e)
        {
            throw new FederationException("Failed to " + what + ": " + e.getMessage(), e);
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
        try
        {
            return Optional.ofNullable(call.get());
        }
        catch (FederationClientException e)
        {
            if (isNotFound(e))
            {
                return Optional.empty();
            }
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
     * Returns whether a failure means "the server has no such record" rather than a real error.
     *
     * @param e the failure reported by the client
     * @return {@code true} when the record simply does not exist
     */
    private static boolean isNotFound(FederationClientException e)
    {
        String message = e.getMessage();
        if (message == null)
        {
            return false;
        }

        String lower = message.toLowerCase();
        return lower.contains("404") || lower.contains("not found") || lower.contains("does not exist");
    }
}
