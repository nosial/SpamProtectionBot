package net.nosial.spb.utilities;

import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.context.HandlerContext;

import java.util.Map;

/**
 * Makes sure a Telegram entity exists on the Federation server before an operation that needs it.
 *
 * <p>Reports and blacklists are refused for an entity the server has never seen, so whoever is
 * about to submit one publishes its target first. What is published follows the chat the event
 * happened in: when that chat has privacy mode on, only the bare identifier is sent; otherwise the
 * entity's properties go with it. A private chat with the bot never applies privacy mode, because
 * whatever the user sends there, such as a forward to report, is addressed to the bot on purpose.
 *
 * <p>Publishing is idempotent, and a bare publish omits metadata entirely rather than sending an
 * empty set, so it never erases properties an earlier, fuller publish recorded.
 */
public final class EntityPublisher
{
    /**
     * Federation host of every Telegram entity.
     */
    private static final String TELEGRAM_HOST = "telegram.org";

    private EntityPublisher()
    {
    }

    /**
     * Publishes a Telegram user or chat as {@code <id>@telegram.org}.
     *
     * @param context the per-update context
     * @param eventChatId the chat the event happened in, whose privacy mode decides what is sent
     * @param telegramId the user or chat id to publish
     * @param source the Telegram {@code User} or {@code Chat} whose properties are sent, or
     *               {@code null} to publish only the identifier
     * @throws FederationException if the server is unavailable or rejected the call
     */
    public static void publish(HandlerContext context, long eventChatId, long telegramId, Object source) throws FederationException
    {
        if (!context.federation().isAuthenticated())
        {
            // Publishing an entity is refused to the bot unconditionally without client
            // permissions, so there is nothing worth attempting here.
            return;
        }

        Map<String, Object> metadata = source != null && !privacyApplies(context, eventChatId) ? FlatMetadata.of(source) : null;
        context.federation().publishEntity(TELEGRAM_HOST, String.valueOf(telegramId), metadata);
    }

    /**
     * Returns whether privacy mode restricts what may be published about an event in the chat.
     *
     * <p>Telegram gives private chats the positive id of the user on the other side, and groups
     * and channels negative ids, so the sign alone tells a private chat apart.
     *
     * @param context the per-update context
     * @param eventChatId the chat the event happened in
     * @return {@code true} when only bare identifiers may be published
     */
    static boolean privacyApplies(HandlerContext context, long eventChatId)
    {
        if (eventChatId > 0)
        {
            return false;
        }
        return context.managers().chatConfigurations().resolve(eventChatId).privacyMode();
    }
}
