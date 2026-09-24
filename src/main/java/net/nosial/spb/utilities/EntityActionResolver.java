package net.nosial.spb.utilities;

import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.spb.enums.ModerationAction;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.enums.JoinProtectionBehavior;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the moderation action to take on a user entity based on the federation query result
 * and the chat's configured behavior, and applies it via the Telegram client.
 *
 * <p>This class is used by both {@code UpdateHandler} (scanning) and
 * {@code JoinProtectionHandler} (join protection), keeping the action-resolution and
 * application logic in one place.
 */
public final class EntityActionResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityActionResolver.class);

    private EntityActionResolver() {}

    /**
     * Resolves the action for the scanning pipeline.
     *
     * @param behavior the configured scanning behavior
     * @param query the entity query result
     * @return the resolved action
     */
    public static ModerationAction resolve(ScanningBehavior behavior, EntityQueryResult query)
    {
        if (behavior == ScanningBehavior.PASSIVE || query == null)
        {
            return ModerationAction.NONE;
        }
        if (query.suggestedAction() == SuggestedAction.PERMANENTLY_BLOCK_ENTITY)
        {
            return behavior == ScanningBehavior.STRICT
                    ? ModerationAction.PERMANENT_BAN : ModerationAction.PERMANENT_RESTRICT;
        }
        if (query.suggestedAction() == SuggestedAction.TEMPORARILY_BLOCK_ENTITY)
        {
            return behavior == ScanningBehavior.STRICT
                    ? ModerationAction.TEMPORARY_BAN : ModerationAction.TEMPORARY_RESTRICT;
        }
        return ModerationAction.NONE;
    }

    /**
     * Resolves the action for the join-protection pipeline.
     *
     * @param behavior the configured join-protection behavior
     * @param query the entity query result
     * @return the resolved action
     */
    public static ModerationAction resolve(JoinProtectionBehavior behavior, EntityQueryResult query)
    {
        if (query == null || query.suggestedAction() == null || behavior == JoinProtectionBehavior.PASSIVE)
        {
            return ModerationAction.NONE;
        }
        return switch (query.suggestedAction())
        {
            case TEMPORARILY_BLOCK_ENTITY -> behavior == JoinProtectionBehavior.STRICT ? ModerationAction.TEMPORARY_BAN : ModerationAction.TEMPORARY_RESTRICT;
            case PERMANENTLY_BLOCK_ENTITY -> behavior == JoinProtectionBehavior.STRICT ? ModerationAction.PERMANENT_BAN : ModerationAction.PERMANENT_RESTRICT;
            default -> ModerationAction.NONE;
        };
    }

    /**
     * Applies the resolved action to a user in a chat. Returns {@code true} when the action
     * was successfully dispatched to Telegram.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the user to act on
     * @param action the resolved action
     * @param query the entity query result (used for lift timestamps)
     * @return {@code true} if the action was applied successfully
     */
    public static boolean apply(HandlerContext context, long chatId, long userId, ModerationAction action, EntityQueryResult query)
    {
        if (action == ModerationAction.NONE || context.telegramClient() == null)
        {
            return false;
        }

        boolean success;
        if (action == ModerationAction.PERMANENT_BAN || action == ModerationAction.TEMPORARY_BAN)
        {
            Long duration = action == ModerationAction.TEMPORARY_BAN ? (long) ModerationActions.temporaryRestrictionUntil(query != null ? query.suggestedLiftTimestamp() : null) : null;
            success = ModerationActions.banUser(context, chatId, userId, duration);
        }
        else
        {
            Long duration = action == ModerationAction.TEMPORARY_RESTRICT ? (long) ModerationActions.temporaryRestrictionUntil(query != null ? query.suggestedLiftTimestamp() : null) : null;
            success = ModerationActions.restrictUser(context, chatId, userId, duration);
        }

        if (success)
        {
            LOGGER.info("Applied {} to user {} in chat {}", action, userId, chatId);
        }

        return success;
    }
}
