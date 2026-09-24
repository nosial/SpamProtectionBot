package net.nosial.spb.utilities;

import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EntityRecord;
import net.nosial.spb.classes.managers.UserManager;
import net.nosial.spb.objects.database.UserIdentity;

/**
 * Resolves Federation entity UUIDs into human-readable Telegram HTML.
 *
 * <p>A resolved entity is rendered as {@code address (uuid)}. Telegram entities use a local user
 * reference when available: a known username becomes {@code @username (uuid)}, while an identified
 * user without a username becomes an inline {@code tg://user} mention. Resolution failures retain
 * a safe UUID fallback so display code never hides the underlying identifier.
 */
public final class EntityResolver
{
    private EntityResolver()
    {
    }

    /**
     * Resolves an entity UUID through Federation and renders it for Telegram HTML.
     *
     * @param federation the Federation server
     * @param users local Telegram identity manager
     * @param entityUuid Federation entity UUID
     * @return rendered address and UUID, or the safe UUID fallback when unresolved
     */
    public static String resolve(FederationService federation, UserManager users, String entityUuid)
    {
        return resolve(federation, users, entityUuid, false);
    }

    /**
     * Resolves an entity unless chat privacy mode requires retaining only its UUID.
     *
     * @param federation the Federation server
     * @param users local Telegram identity manager
     * @param entityUuid Federation entity UUID
     * @param privacyMode whether identity resolution must be suppressed
     * @return rendered address and UUID, or the safe UUID fallback when unresolved or private
     */
    public static String resolve(FederationService federation, UserManager users, String entityUuid, boolean privacyMode)
    {
        if (entityUuid == null || entityUuid.isBlank())
        {
            return "Unknown";
        }
        if (privacyMode || federation == null || !federation.isAvailable())
        {
            return uuidFallback(entityUuid);
        }

        try
        {
            return federation.entity(entityUuid)
                    .map(record -> display(record, users, false))
                    .orElseGet(() -> uuidFallback(entityUuid));
        }
        catch (FederationException e)
        {
            return uuidFallback(entityUuid);
        }
    }

    /**
     * Renders an already-resolved Federation entity without another Federation request.
     *
     * @param entity Federation entity record
     * @param users local Telegram identity manager
     * @return rendered address and UUID
     */
    public static String display(EntityRecord entity, UserManager users)
    {
        return display(entity, users, false);
    }

    /**
     * Renders an already-resolved entity while respecting chat privacy mode.
     *
     * @param entity Federation entity record
     * @param users local Telegram identity manager
     * @param privacyMode whether identity resolution must be suppressed
     * @return rendered address and UUID
     */
    public static String display(EntityRecord entity, UserManager users, boolean privacyMode)
    {
        if (entity == null)
        {
            return "Unknown";
        }
        if (privacyMode)
        {
            return uuidFallback(entity.uuid());
        }

        String uuid = entity.uuid();
        String suffix = uuid == null || uuid.isBlank() ? "" : " (<code>" + HtmlEscape.escape(uuid) + "</code>)";
        if ("telegram.org".equalsIgnoreCase(entity.host()))
        {
            Long telegramUserId = telegramUserId(entity.id());
            if (telegramUserId != null && users != null)
            {
                UserIdentity user = users.getUser(telegramUserId).orElse(null);
                if (user != null)
                {
                    if (user.username() != null && !user.username().isBlank())
                    {
                        return "@" + HtmlEscape.escape(user.username()) + suffix;
                    }
                    return "<a href=\"tg://user?id=" + telegramUserId + "\">" + telegramUserId + "</a>" + suffix;
                }
            }
        }

        String address = entityAddress(entity);
        return address == null ? uuidFallback(uuid) : HtmlEscape.escape(address) + suffix;
    }

    private static String entityAddress(EntityRecord entity)
    {
        if (entity.id() == null || entity.id().isBlank())
        {
            return entity.host();
        }
        if (entity.host() == null || entity.host().isBlank())
        {
            return entity.id();
        }
        return entity.id() + "@" + entity.host();
    }

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

    private static String uuidFallback(String entityUuid)
    {
        return entityUuid == null || entityUuid.isBlank() ? "Unknown" : "<code>" + HtmlEscape.escape(entityUuid) + "</code>";
    }
}
