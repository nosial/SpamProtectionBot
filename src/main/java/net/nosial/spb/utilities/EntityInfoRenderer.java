package net.nosial.spb.utilities;

import net.nosial.jfederation.records.BlacklistRecord;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.jfederation.records.EntityRecord;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders what Federation knows about a Telegram user, as HTML.
 *
 * <p>The same summary appears in two places — the {@code /info} reply and the secretary contact
 * page — and both must say the same thing about the same person. Keeping the rendering here is
 * what guarantees that, and stops the secretary package from reaching into the federation package
 * to borrow it.
 */
public final class EntityInfoRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityInfoRenderer.class);

    /** Federation addresses every Telegram entity under this host. */
    private static final String TELEGRAM_ENTITY_SUFFIX = "@telegram.org";

    /**
     * Appends the same Federation information shown by {@code /info} for a secretary contact.
     * Contact state is rendered by the secretary settings handler alongside this section.
     *
     * @param context the services used to query Federation
     * @param html the destination HTML builder
     * @param contactId the Telegram user id of the contact
     * @param lang the owner's language
     */
    public static void appendContactInfo(HandlerContext context, StringBuilder html, long contactId, Language lang)
    {
        LanguageManager lm = context.languages();
        html.append(lm.get(lang, "info", "header"));
        String address = contactId + TELEGRAM_ENTITY_SUFFIX;
        html.append(lm.get(lang, "info", "queried", HtmlEscape.escape(address))).append('\n');

        if (!context.federation().isAvailable())
        {
            html.append(lm.get(lang, "info", "unavailable_no_federation"));
            return;
        }

        EntityRecord entity = fetchEntity(context, address);
        if (entity == null)
        {
            html.append(lm.get(lang, "info", "entity_not_found", HtmlEscape.escape(address)));
            return;
        }

        appendEntityFields(html, context, entity, false, lang);
        html.append(lm.get(lang, "info", "reputation", formatReputation(entity.reputation(), lm, lang))).append('\n');
        html.append(lm.get(lang, "info", "whitelisted", lm.get(lang, "general", entity.whitelisted() ? "yes" : "no"))).append('\n');

        if (entity.relationshipType() != null || entity.relationshipEntity() != null)
        {
            StringBuilder relationship = new StringBuilder();

            if (entity.relationshipType() != null)
            {
                relationship.append(HtmlEscape.escape(MessageHelper.humanize(entity.relationshipType().name())));
            }

            if (entity.relationshipEntity() != null)
            {
                if (!relationship.isEmpty())
                {
                    relationship.append(' ');
                }
                relationship.append(EntityResolver.resolve(context.federation(), context.managers().users(), entity.relationshipEntity(), false));
            }

            html.append(lm.get(lang, "info", "relationship", relationship)).append('\n');
        }

        html.append(lm.get(lang, "info", "created", HtmlEscape.escape(MessageHelper.formatTimestamp(entity.created())))).append('\n');
        html.append(lm.get(lang, "info", "updated", HtmlEscape.escape(MessageHelper.formatTimestamp(entity.updated())))).append('\n');
        appendEntityQuery(html, fetchEntityQuery(context, address), lm, lang);
        appendBlacklists(context, html, entity.uuid(), lm, lang);
        appendReports(context, html, entity.uuid(), lm, lang);
    }

/**
     * Fetches the entity record from the Federation server, or {@code null} when the entity does
     * not exist or the server cannot answer.
     *
     * @param context the per-update command context
     * @param identifier the entity UUID, SHA-256 hash, or entity address
     * @return the entity record, or {@code null}
     */
    public static EntityRecord fetchEntity(HandlerContext context, String identifier)
    {
        try
        {
            return context.federation().entity(identifier).orElse(null);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch entity {}: {}", identifier, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the query result for an entity from the Federation server. Returns the result
     * if available, or {@code null} when the entity does not exist or an error occurs during
     * the query process.
     *
     * @param context the context containing services to perform the query
     * @param identifier the unique identifier of the entity, which can be a UUID, SHA-256 hash, or an entity address
     * @return the result of the entity query as an {@link EntityQueryResult}, or {@code null} if the entity does not exist or the query fails
     */
    public static EntityQueryResult fetchEntityQuery(HandlerContext context, String identifier)
    {
        try
        {
            return context.federation().queryEntity(identifier).orElse(null);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to query entity {}: {}", identifier, e.getMessage());
            return null;
        }
    }

    /**
     * Appends the entity identity fields: the entity address and UUID are always shown, each on
     * their own line. For a Telegram entity ({@code <id>@telegram.org}) whose user is registered
     * locally, the resolved Telegram username and an inline mention are shown alongside. When a
     * Telegram entity cannot be resolved locally, or the host is not Telegram, the identity
     * metadata published on the Federation record is used as a fallback.
     *
     * @param html the response builder
     * @param context the services used to query Federation
     * @param entity the fetched entity record
     * @param privacyMode whether identity resolution must be suppressed
     * @param lang the target language
     */
    public static void appendEntityFields(StringBuilder html, HandlerContext context, EntityRecord entity, boolean privacyMode, Language lang)
    {
        String address = entityAddress(entity);
        if (address != null)
        {
            html.append(context.languages().get(lang, "info", "entity_address", HtmlEscape.escape(address))).append('\n');
        }
        if (entity.uuid() != null && !entity.uuid().isBlank())
        {
            html.append(context.languages().get(lang, "info", "entity_uuid", HtmlEscape.escape(entity.uuid()))).append('\n');
        }

        if (privacyMode)
        {
            return;
        }

        if ("telegram.org".equalsIgnoreCase(entity.host()) && context.database() != null)
        {
            Long telegramUserId = telegramUserId(entity.id());
            if (telegramUserId != null && context.managers().users() != null)
            {
                UserIdentity user = context.managers().users().getUser(telegramUserId).orElse(null);
                if (user != null)
                {
                    if (user.username() != null && !user.username().isBlank())
                    {
                        html.append(context.languages().get(lang, "info", "telegram_username", "@" + HtmlEscape.escape(user.username()))).append('\n');
                    }
                    html.append(context.languages().get(lang, "info", "telegram_id",
                            "<a href=\"tg://user?id=" + telegramUserId + "\">" + telegramUserId + "</a>")).append('\n');
                    appendMetadata(html, entity.getMetadata(), context.languages(), lang, false);
                    appendOperatorInfo(html, context, telegramUserId, context.languages(), lang);
                    return;
                }
            }
        }

        appendMetadata(html, entity.getMetadata(), context.languages(), lang, true);
    }

    /**
     * Appends up to five active blacklist records for the entity.
     *
     * @param context the per-update command context
     * @param html the response builder
     * @param entityUuid the entity UUID
     */
    public static void appendBlacklists(HandlerContext context, StringBuilder html, String entityUuid, LanguageManager lm, Language lang)
    {
        if (entityUuid == null)
        {
            return;
        }

        List<BlacklistRecord> blacklists;

        try
        {
            blacklists = context.federation().activeBlacklists(entityUuid);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to load blacklists for entity {}: {}", entityUuid, e.getMessage());
            return;
        }

        if (blacklists.isEmpty())
        {
            return;
        }

        html.append(lm.get(lang, "info", "blacklists_header"));
        for (BlacklistRecord blacklist : blacklists)
        {
            html.append(lm.get(lang, "info", "blacklist_entry",
                    blacklist.type() != null ? HtmlEscape.escape(MessageHelper.humanize(blacklist.type().name())) : lm.get(lang, "general", "unknown"),
                    HtmlEscape.escape(MessageHelper.formatTimestamp(blacklist.created())),
                    HtmlEscape.escape(blacklist.uuid())));
        }
    }

    /**
     * Appends up to five recent reports for the entity.
     *
     * @param context the per-update command context
     * @param html the response builder
     * @param entityUuid the entity UUID
     */
    public static void appendReports(HandlerContext context, StringBuilder html, String entityUuid, LanguageManager lm, Language lang)
    {
        if (entityUuid == null)
        {
            return;
        }
        List<ReportRecord> reports;
        try
        {
            reports = context.federation().entityReports(entityUuid, 5);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to load reports for entity {}: {}", entityUuid, e.getMessage());
            return;
        }
        if (reports.isEmpty())
        {
            return;
        }
        html.append(lm.get(lang, "info", "reports_header"));
        for (ReportRecord report : reports)
        {
            html.append(lm.get(lang, "info", "report_entry",
                    report.incidentType() != null
                            ? HtmlEscape.escape(MessageHelper.humanize(report.incidentType().name()))
                            : lm.get(lang, "general", "unknown"),
                    lm.get(lang, "general", report.opened() ? "open" : "closed"),
                    HtmlEscape.escape(MessageHelper.formatTimestamp(report.created())),
                    HtmlEscape.escape(report.uuid())));
        }
    }

    /**
     * Formats a reputation score without needless decimals.
     *
     * @param reputation the reputation score
     * @return the formatted score
     */
    public static String formatReputation(double reputation, LanguageManager lm, Language lang)
    {
        if (Double.isNaN(reputation) || Double.isInfinite(reputation))
        {
            return lm.get(lang, "general", "unknown");
        }
        if (reputation == Math.floor(reputation))
        {
            return String.valueOf((long) reputation);
        }
        return String.format(Locale.ROOT, "%.2f", reputation);
    }

    /**
     * Appends the current recommendation and aggregate result counts returned by Federation's
     * entity-query endpoint.
     *
     * @param html the response builder
     * @param entityQuery the Federation query result, or {@code null} when unavailable
     */
    public static void appendEntityQuery(StringBuilder html, EntityQueryResult entityQuery, LanguageManager lm, Language lang)
    {
        html.append(lm.get(lang, "info", "assessment_header"));
        if (entityQuery == null)
        {
            html.append(lm.get(lang, "info", "assessment_status_unavailable"));
            return;
        }

        if (entityQuery.suggestedAction() == null)
        {
            html.append(lm.get(lang, "info", "assessment_suggested_action_none"));
        }
        else
        {
            html.append(lm.get(lang, "info", "assessment_suggested_action", HtmlEscape.escape(MessageHelper.humanize(entityQuery.suggestedAction().name()))));
        }

        if (entityQuery.suggestedLiftTimestamp() != null)
        {
            html.append(lm.get(lang, "info", "assessment_suggested_lift", HtmlEscape.escape(MessageHelper.formatTimestamp(entityQuery.suggestedLiftTimestamp()))));
        }

        html.append(lm.get(lang, "info", "assessment_related_entities", entityQuery.relatedEntities().size()));
        html.append(lm.get(lang, "info", "assessment_active_blacklists", entityQuery.activeBlacklists().size()));
    }

    /**
     * Appends the identity metadata stored on the entity (username, first and last name, and bot
     * flag) when present.
     *
     * @param html the response builder
     * @param metadata the entity metadata map
     * @param includeUsername whether to render the metadata username; suppressed when a resolved
     *                        local Telegram user already supplies an equivalent username field
     */
    private static void appendMetadata(StringBuilder html, Map<String, Object> metadata, LanguageManager lm, Language lang, boolean includeUsername)
    {
        Object username = metadata.get("username");
        if (includeUsername && username != null && !String.valueOf(username).isBlank())
        {
            html.append(lm.get(lang, "info", "username", HtmlEscape.escape(String.valueOf(username)))).append('\n');
        }
        Object firstName = metadata.get("first_name");
        if (firstName != null && !String.valueOf(firstName).isBlank())
        {
            html.append(lm.get(lang, "info", "first_name", HtmlEscape.escape(String.valueOf(firstName)))).append('\n');
        }
        Object lastName = metadata.get("last_name");
        if (lastName != null && !String.valueOf(lastName).isBlank())
        {
            html.append(lm.get(lang, "info", "last_name", HtmlEscape.escape(String.valueOf(lastName)))).append('\n');
        }
        if (metadata.containsKey("is_bot"))
        {
            html.append(lm.get(lang, "info", "bot", lm.get(lang, "general", Boolean.TRUE.equals(metadata.get("is_bot")) ? "yes" : "no"))).append('\n');
        }
    }

    /**
     * Appends a line identifying the target as an authenticated Federation operator when the
     * Telegram user has stored operator credentials.
     *
     * @param html the response builder
     * @param context the services used to query the operator store
     * @param telegramUserId the resolved Telegram user id of the entity
     * @param lm the language manager
     * @param lang the target language
     */
    private static void appendOperatorInfo(StringBuilder html, HandlerContext context, long telegramUserId,
                                           LanguageManager lm, Language lang)
    {
        context.managers().operators().getOperator(telegramUserId)
                .ifPresent(identity -> html.append(lm.get(lang, "info", "operator",
                        HtmlEscape.escape(identity.operatorUuid()))).append('\n'));
    }

    /**
     * Builds the entity address as {@code id@host}, omitting whichever part is blank.
     *
     * @param entity the entity record
     * @return the entity address, or {@code null} when both id and host are blank
     */
    private static String entityAddress(EntityRecord entity)
    {
        if (entity.id() == null || entity.id().isBlank())
        {
            return entity.host() == null || entity.host().isBlank() ? null : entity.host();
        }

        if (entity.host() == null || entity.host().isBlank())
        {
            return entity.id();
        }

        return entity.id() + "@" + entity.host();
    }

    /**
     * Parses a numeric entity identifier into a Telegram user id, or {@code null} when the
     * identifier is not a number.
     *
     * @param identifier the entity id
     * @return the user id, or {@code null}
     */
    private static Long telegramUserId(String identifier)
    {
        if (identifier == null || identifier.isBlank())
        {
            return null;
        }

        try
        {
            return Long.parseLong(identifier);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
