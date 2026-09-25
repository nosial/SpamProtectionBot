package net.nosial.spb.classes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.objects.context.ConfigurationContext;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * The base class every handler extends.
 *
 * <p>A handler declares what it serves with the {@link UpdateHandler} annotation and implements
 * {@link #handle(HandlerContext)}; the registry discovers it and the dispatcher invokes it on a
 * worker thread. Handlers never construct the services they use — the {@link HandlerContext}
 * carries the Telegram client, the database and its managers, the configuration, the Federation
 * server, the translations, the open dialogs, the shared caches, and the bot's own identity.
 *
 * <p><strong>Thread safety.</strong> One instance per handler class is shared by every worker
 * thread, so implementations must be stateless: keep per-update state in local variables, and put
 * anything that must outlive a single update in the database, a session manager, or the context's
 * cache.
 *
 * <p>The protected helpers below are the operations nearly every handler needs — chat type and
 * permission checks, language resolution, replies (ephemeral in groups, regular in private chats),
 * inline keyboards, callback acknowledgement, and message editing. Most delegate to
 * {@link net.nosial.spb.utilities.MessageHelper}, which holds the implementations so that
 * non-handler code can use them too.
 */
public abstract class Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);

    /**
     * Processes the update carried by the context.
     *
     * <p>Called on a worker thread, only when the handler's declared filters and
     * {@link #accepts(HandlerContext)} both matched. Implementations may throw: the dispatcher
     * logs the failure and moves on, so one misbehaving handler can never stall the bot. They must
     * however not block indefinitely, as a blocked worker is a worker the queue cannot use.
     *
     * @param context the per-update context
     * @throws TelegramApiException if answering the update fails
     */
    public abstract void handle(HandlerContext context) throws TelegramApiException;

    /**
     * Narrows the annotation's declared match at runtime.
     *
     * <p>The annotation expresses what can be decided from the shape of the update alone. Anything
     * that needs the update's content or the bot's state — "only in group chats", "only from an
     * administrator", "only while a session is open" — is decided here instead. Returning
     * {@code false} leaves the update to the next handler in priority order.
     *
     * @param context the per-update context
     * @return {@code true} to handle the update, {@code false} to pass it on
     */
    public boolean accepts(HandlerContext context) {
        return true;
    }

    /**
     * Returns the name used for this handler in log messages.
     *
     * @return the handler name
     */
    public String name() {
        return getClass().getSimpleName();
    }

    /**
     * Returns whether the update is a message invoking the named command (with or without
     * parameters and bot-name suffix).
     *
     * @param update the incoming update
     * @param commandName the command name without the leading slash (e.g. {@code start})
     * @return {@code true} when the update invokes the command
     */
    protected static boolean isCommand(Update update, String commandName)
    {
        Message message = update.getMessage();
        if (message == null || message.getText() == null)
        {
            return false;
        }

        String command = message.getText().trim().split("\\s+")[0].toLowerCase();
        return command.equals("/" + commandName) || command.startsWith("/" + commandName + "@");
    }

    /**
     * Returns the payload that follows the leading command token, or {@code null} when the message
     * carries no payload.
     *
     * <p>The leading command token is stripped regardless of whether it was invoked as
     * {@code /command payload} or {@code /command@bot payload}: both forms place the payload after
     * the first whitespace, so the optional {@code @bot} suffix is handled implicitly. The payload
     * is returned trimmed and may itself contain whitespace.
     *
     * @param message the incoming command message
     * @return the payload, or {@code null} when there is none
     */
    protected static String getMessagePayload(Message message)
    {
        return MessageHelper.getMessagePayload(message);
    }

    /**
     * Returns the payload following the command only when it is exactly one whitespace-separated
     * token, or {@code null} when there is no payload or the payload contains whitespace.
     *
     * @param message the incoming command message
     * @return the single-token payload, or {@code null}
     */
    protected static String singleArgument(Message message)
    {
        return MessageHelper.singleArgument(message);
    }

    /**
     * Creates an inline button that carries callback data.
     *
     * @param text the button label
     * @param callbackData the callback data sent when the button is pressed
     * @return the inline button
     */
    protected static InlineKeyboardButton button(String text, String callbackData)
    {
        return MessageHelper.button(text, callbackData);
    }

    /**
     * Creates an inline button that opens an external URL.
     *
     * @param text the button label
     * @param url the button URL
     * @return the inline button
     */
    protected static InlineKeyboardButton urlButton(String text, String url)
    {
        return MessageHelper.urlButton(text, url);
    }

    /**
     * Builds an inline keyboard from the given rows.
     *
     * @param rows the keyboard rows
     * @return the inline keyboard markup
     */
    protected static InlineKeyboardMarkup markup(InlineKeyboardRow... rows)
    {
        return MessageHelper.markup(rows);
    }

    /**
     * Builds an inline keyboard whose buttons all sit on a single row.
     *
     * @param buttons the buttons of the single row
     * @return the inline keyboard markup
     */
    protected static InlineKeyboardMarkup singleRowMarkup(InlineKeyboardButton... buttons)
    {
        return MessageHelper.singleRowMarkup(buttons);
    }

    /**
     * Arranges the given buttons into rows of at most two buttons each.
     *
     * @param buttons the buttons to arrange
     * @return the keyboard rows
     */
    protected static List<InlineKeyboardRow> rowsOfTwo(List<InlineKeyboardButton> buttons)
    {
        return MessageHelper.rowsOfTwo(buttons);
    }

    /**
     * Returns whether the message was sent in a private (one-on-one) chat.
     *
     * @param message the incoming message
     * @return {@code true} when the chat type is {@code "private"}
     */
    protected static boolean isPrivateChat(Message message)
    {
        return "private".equals(message.getChat().getType());
    }

    /**
     * Returns whether the message was sent in a group or supergroup chat.
     *
     * @param message the incoming message
     * @return {@code true} when the chat type is {@code "group"} or {@code "supergroup"}
     */
    protected static boolean isGroupChat(Message message)
    {
        String chatType = message.getChat().getType();
        return "group".equals(chatType) || "supergroup".equals(chatType);
    }

    /**
     * Returns whether the message's author is an administrator of the message's chat with the
     * Change Group Information permission, according to the cached administrator list.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @return {@code true} when the author is a cached administrator who can change the chat's information
     */
    protected static boolean isChangeInformationAdministrator(HandlerContext context, Message message)
    {
        if (message.getFrom() == null)
        {
            return false;
        }
        return isChangeInformationAdministrator(context, message.getChatId(), message.getFrom().getId());
    }

    /**
     * Returns whether the given user is an administrator of the given chat with the Change Group
     * Information permission, according to the cached administrator list.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to test
     * @return {@code true} when the user is a cached administrator who can change the chat's information
     */
    protected static boolean isChangeInformationAdministrator(HandlerContext context, long chatId, long userId)
    {
        return hasAdministrator(context, chatId, userId, AdminInfo::canChangeInfo);
    }

    /**
     * Returns whether the given user is a cached moderator of the given chat: the owner, or an
     * administrator who can delete messages or restrict members.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to test
     * @return {@code true} when the user is a cached moderator
     */
    protected static boolean isChatAdministrator(HandlerContext context, long chatId, long userId)
    {
        return hasAdministrator(context, chatId, userId, AdminInfo::isModerator);
    }

    /**
     * Returns whether the bot itself is an administrator of the given chat with the Change Group
     * Information permission, according to the cached administrator list.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @return {@code true} when the bot is a cached administrator who can change the chat's information
     */
    protected static boolean isBotChangeInformationAdministrator(HandlerContext context, long chatId)
    {
        return hasAdministrator(context, chatId, context.botUserId(), AdminInfo::canChangeInfo);
    }

    /**
     * Returns whether the message's author is the owner of the message's chat, according to the
     * cached administrator list.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @return {@code true} when the author is the cached chat owner
     */
    protected static boolean isChatOwner(HandlerContext context, Message message)
    {
        if (message.getFrom() == null)
        {
            return false;
        }
        return hasAdministrator(context, message.getChatId(), message.getFrom().getId(), AdminInfo::isOwner);
    }

    /**
     * Returns whether the cached administrator list of a chat holds the given user with the given
     * permission.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to test
     * @param permission the permission the administrator must hold
     * @return {@code true} when the user is a cached administrator holding the permission
     */
    private static boolean hasAdministrator(HandlerContext context, long chatId, long userId, Predicate<AdminInfo> permission)
    {
        List<AdminInfo> administrators = context.chatAdmins().getIfPresent(chatId);
        return administrators != null && administrators.stream().anyMatch(a -> a.id() == userId && permission.test(a));
    }

    /**
     * Fetches the administrator list of a chat from Telegram, bypassing the cache, and stores it in
     * the cache for everything that follows.
     *
     * <p>Used before permission checks the user acts on directly, such as opening the settings menu,
     * so a promotion or demotion is honoured at once instead of after the cached list expires.
     * When the fetch fails the cached list, if any, is left in place and used instead.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     */
    protected static void refreshAdministrators(HandlerContext context, long chatId)
    {
        try
        {
            context.chatAdmins().put(chatId, loadAdministrators(context, chatId));
        }
        catch (Cache.LoadFailedException e)
        {
            // Already logged by the loader.
        }
    }

    /**
     * Fetches every administrator of the given chat from the Telegram API, with the permissions each
     * holds. The result fills {@link HandlerContext#chatAdmins()}, which drives permission checks,
     * moderation eligibility and private moderator-notification delivery without per-check API calls.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @return the chat's administrators
     * @throws Cache.LoadFailedException If the fetch failed, so the failure is not cached as an empty list
     */
    public static List<AdminInfo> loadAdministrators(HandlerContext context, long chatId)
    {
        List<ChatMember> members;
        try
        {
            members = context.telegramClient().execute(GetChatAdministrators.builder().chatId(chatId).build());
        }
        catch (TelegramApiException | RuntimeException e)
        {
            // Any failure of the call itself, not only an API error, must stay uncached.
            LOGGER.warn("Failed to load administrators for chat {}: {}", chatId, e.getMessage());
            throw new Cache.LoadFailedException("Failed to load administrators for chat " + chatId, e);
        }

        List<AdminInfo> administrators = new ArrayList<>();
        for (ChatMember member : members)
        {
            long userId = member.getUser().getId();
            if (member instanceof ChatMemberOwner)
            {
                administrators.add(new AdminInfo(userId, true, true, true, true));
            }
            else if (member instanceof ChatMemberAdministrator administrator)
            {
                administrators.add(new AdminInfo(userId, false,
                        Boolean.TRUE.equals(administrator.getCanDeleteMessages()),
                        Boolean.TRUE.equals(administrator.getCanRestrictMembers()),
                        Boolean.TRUE.equals(administrator.getCanChangeInfo())));
            }
        }
        return administrators;
    }

    /**
     * Resolves the effective language for a command update. In group chats returns the chat
     * language; in private chats returns the user language.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @return the resolved language
     */
    protected static Language resolveLanguage(HandlerContext context, Message message)
    {
        Language defaultLang = context.languages().defaultLanguage();
        if (isGroupChat(message))
        {
            return context.managers().languagePreferences()
                    .getChatLanguage(message.getChatId());
        }
        else if (message.getFrom() != null)
        {
            return context.managers().languagePreferences()
                    .getUserLanguage(message.getFrom().getId());
        }
        return defaultLang;
    }

    /**
     * Sends an HTML reply to the command message's chat. In group and supergroup chats the reply
     * is ephemeral, so only the message's author can see it; in private chats a regular message is
     * sent. The forum topic of the command message is always preserved.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @param html the HTML text to send
     * @throws TelegramApiException if the message cannot be sent
     */
    protected static void sendHtml(HandlerContext context, Message message, String html,
                                    boolean ephemeral, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        boolean useEphemeral = ephemeral || (isGroupChat(message) && message.getFrom() != null);
        boolean hasFrom = message.getFrom() != null;
        var builder = SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .messageThreadId(MessageHelper.topicId(message))
                .text(html)
                .parseMode(ParseMode.HTML);
        if (useEphemeral && hasFrom)
        {
            builder.receiverUserId(message.getFrom().getId());
        }
        if (markup != null)
        {
            builder.replyMarkup(markup);
        }
        context.telegramClient().execute(builder.build());
    }

    /**
     * Sends a reply to a given message with HTML content.
     *
     * @param context the handler context associated with the current execution
     * @param message the message to which the reply is being sent
     * @param html the HTML content to include in the reply
     * @throws TelegramApiException if an error occurs while sending the reply
     */
    protected static void sendReply(HandlerContext context, Message message, String html) throws TelegramApiException
    {
        sendHtml(context, message, html, false, null);
    }

    /**
     * Sends an ephemeral message to a specific receiver in a chat thread using the Telegram API.
     *
     * @param context The handler context that provides access to the Telegram client.
     * @param message The message object containing details of the chat, sender, and receiver information.
     * @param text The text content of the ephemeral message to be sent.
     * @throws TelegramApiException If there is an error while sending the message through the Telegram API.
     */
    protected static void sendEphemeral(HandlerContext context, Message message, String text) throws TelegramApiException
    {
        if (message.getFrom() == null)
        {
            return;
        }

        context.telegramClient().execute(SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .messageThreadId(MessageHelper.topicId(message))
                .receiverUserId(message.getFrom().getId())
                .text(text)
                .build());
    }

    /**
     * Sends an ephemeral HTML response to a specified message in a Telegram chat.
     *
     * @param context the context of the handler managing the request
     * @param message the original message to which the response is being sent
     * @param html the HTML content to be sent as the response
     * @throws TelegramApiException if an error occurs while sending the message
     */
    protected static void sendEphemeralHtml(HandlerContext context, Message message, String html) throws TelegramApiException
    {
        sendHtml(context, message, html, true, null);
    }

    /**
     * Sends a message as either ephemeral or regular based on the specified flag.
     *
     * @param context the handler context used for managing the message operation
     * @param message the incoming message to which this message is a response
     * @param text the text content of the message to be sent
     * @param ephemeral if true, sends the message as ephemeral; if false, sends it as a regular reply
     * @throws TelegramApiException if there is an error during the message sending process
     */
    protected static void sendEphemeralOrRegular(HandlerContext context, Message message, String text, boolean ephemeral) throws TelegramApiException
    {
        if (ephemeral)
        {
            sendEphemeral(context, message, text);
        }
        else
        {
            sendReply(context, message, text);
        }
    }

    /**
     * Deletes a command message in a chat if it is not an ephemeral message.
     * This method interacts with the Telegram API to remove the specified message.
     *
     * @param context the handler context providing access to the Telegram client
     * @param message the message to be deleted, containing chat and message details
     */
    protected static void deleteCommandMessage(HandlerContext context, Message message)
    {
        if (message.getEphemeralMessageId() != null)
        {
            return;
        }
        try
        {
            context.telegramClient().execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
                    .builder()
                    .chatId(String.valueOf(message.getChatId()))
                    .messageId(message.getMessageId())
                    .build());
        }
        catch (TelegramApiException e)
        {
            LOGGER.debug("Could not delete command message in chat {}: {}", message.getChatId(), e.getMessage());
        }
    }
    /**
     * Resolves the Federation server information from the client instance, or {@code null} when
     * federation is not configured or the server is unreachable.
     *
     * @param context the per-update command context
     * @return the server information, or {@code null}
     */
    protected static ServerInformation serverInformation(HandlerContext context)
    {
        return MessageHelper.serverInformation(context);
    }

    /**
     * Returns the Federation server endpoint as it is known to the client instance, guaranteed to
     * end with a trailing slash.
     *
     * @param context the per-update command context
     * @return the server host URL
     */
    protected static String serverHost(HandlerContext context)
    {
        return MessageHelper.serverHost(context);
    }

    /**
     * Executes an API method, logging its payload beforehand and again if Telegram rejects it.
     *
     * @param context the command context
     * @param label a short label identifying the method in the logs
     * @param method the method to execute
     * @return the method's result
     * @throws TelegramApiException if the method fails; the payload has already been logged
     */
    protected static <T extends Serializable, M extends BotApiMethod<T>> T execute(HandlerContext context, String label, M method) throws TelegramApiException
    {
        MessageHelper.logOutgoing(context, label, method);
        try
        {
            return context.telegramClient().execute(method);
        }
        catch (TelegramApiException e)
        {
            MessageHelper.logFailed(context, label, method, e);
            throw e;
        }
    }

    /**
     * Executes an API method on a best-effort basis: a failure is logged, as by
     * {@link #execute}, and otherwise ignored.
     *
     * @param context the command context
     * @param label a short label identifying the method in the logs
     * @param method the method to execute
     */
    protected static <T extends Serializable, M extends BotApiMethod<T>> void tryExecute(HandlerContext context, String label, M method)
    {
        try
        {
            execute(context, label, method);
        }
        catch (TelegramApiException ignored)
        {
        }
    }

    /**
     * Sends an HTML reply to a message, in the same chat and forum topic. Unlike
     * {@link #sendHtml}, the reply is never ephemeral.
     *
     * @param context the command context
     * @param label a short label identifying the reply in the logs
     * @param message the message to reply to
     * @param html the HTML text to send
     * @param markup optional inline keyboard
     * @return the sent message
     * @throws TelegramApiException if the reply cannot be sent
     */
    protected static Message replyHtml(HandlerContext context, String label, Message message, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        var builder = SendMessage.builder().chatId(String.valueOf(message.getChatId()))
                .replyToMessageId(message.getMessageId())
                .messageThreadId(MessageHelper.topicId(message))
                .text(html)
                .parseMode(ParseMode.HTML);
        if (markup != null)
        {
            builder.replyMarkup(markup);
        }
        return execute(context, label, builder.build());
    }

    /**
     * Returns the callback query from the given update.
     *
     * @param update the incoming update
     * @return the callback query
     */
    protected static CallbackQuery callbackQuery(Update update)
    {
        return update.getCallbackQuery();
    }

    /**
     * Returns the callback data from the given update, or {@code null} when absent.
     *
     * @param update the incoming update
     * @return the callback data
     */
    protected static String callbackData(Update update)
    {
        CallbackQuery query = callbackQuery(update);
        return query != null ? query.getData() : null;
    }

    /**
     * Answers the callback query to stop the loading indicator on the button. No notification is
     * shown to the user.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @throws TelegramApiException if the answer cannot be sent
     */
    protected static void answer(HandlerContext context, CallbackQuery callbackQuery) throws TelegramApiException
    {
        context.telegramClient().execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .showAlert(false)
                .build());
    }

    /**
     * Answers the callback query with a toast message shown at the top of the chat, to give visual
     * feedback in addition to any message edit.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @param text the toast text
     * @throws TelegramApiException if the answer cannot be sent
     */
    protected static void answer(HandlerContext context, CallbackQuery callbackQuery, String text) throws TelegramApiException
    {
        context.telegramClient().execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .text(text)
                .showAlert(false)
                .build());
    }

    /**
     * Answers the callback query with an alert dialog shown in the middle of the screen, used for
     * errors or state changes that require the user's attention.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @param text the alert text
     * @throws TelegramApiException if the answer cannot be sent
     */
    protected static void answerAlert(HandlerContext context, CallbackQuery callbackQuery, String text) throws TelegramApiException
    {
        context.telegramClient().execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.getId())
                .text(text)
                .showAlert(true)
                .build());
    }

    /**
     * Edits a message to show new HTML text and optional markup, handling the ephemeral message
     * branching transparently.
     *
     * @param context the per-update command context
     * @param message the message to edit
     * @param callbackQuery the callback query (for receiver user id in ephemeral edits)
     * @param html the new HTML text
     * @param markup the new inline keyboard, or {@code null} to remove
     * @throws TelegramApiException if the edit cannot be sent
     */
    protected static void editMessage(HandlerContext context, Message message, CallbackQuery callbackQuery, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        if (message.getEphemeralMessageId() != null && callbackQuery.getFrom() != null)
        {
            var builder = EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(message.getChatId()))
                    .receiverUserId(callbackQuery.getFrom().getId())
                    .ephemeralMessageId(message.getEphemeralMessageId())
                    .text(html)
                    .parseMode(ParseMode.HTML);
            if (markup != null)
            {
                builder.replyMarkup(markup);
            }
            context.telegramClient().execute(builder.build());
            return;
        }

        var builder = EditMessageText.builder()
                .chatId(String.valueOf(message.getChatId()))
                .messageId(message.getMessageId())
                .text(html)
                .parseMode(ParseMode.HTML);
        if (markup != null)
        {
            builder.replyMarkup(markup);
        }
        context.telegramClient().execute(builder.build());
    }

    /**
     * Edits a message to show new HTML text and optional markup, choosing between ephemeral and
     * regular editing based on the session's ephemeral flag.
     *
     * <p>When the session is ephemeral, the edit uses the session's {@code ephemeralMessageId}
     * and {@code userId} rather than the callback query's message fields. This is needed because
     * the configuration menu may be sent as an ephemeral message in a group chat.
     *
     * @param context the per-update command context
     * @param message the message to edit
     * @param callbackQuery the callback query
     * @param html the new HTML text
     * @param markup the new inline keyboard, or {@code null} to remove
     * @throws TelegramApiException if the edit cannot be sent
     */
    protected static void editMessage(HandlerContext context, Message message, CallbackQuery callbackQuery, ConfigurationContext session, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        if (session.ephemeral() && session.ephemeralMessageId() != null)
        {
            var builder = EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(message.getChatId()))
                    .receiverUserId(session.userId())
                    .ephemeralMessageId(session.ephemeralMessageId())
                    .text(html)
                    .parseMode(ParseMode.HTML);
            if (markup != null)
            {
                builder.replyMarkup(markup);
            }
            context.telegramClient().execute(builder.build());
            return;
        }

        editMessage(context, message, callbackQuery, html, markup);
    }

    /**
     * Returns the message carried by the callback query.
     *
     * @param callbackQuery the incoming callback query
     * @return the message, or {@code null}
     */
    protected static Message requireMessage(CallbackQuery callbackQuery)
    {
        if (callbackQuery.getMessage() instanceof Message message)
        {
            return message;
        }
        return null;
    }

    /**
     * Resolves the effective language for a callback query. In group chats returns the chat
     * language; in private chats returns the user language.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @return the resolved language
     */
    protected static Language resolveLanguage(HandlerContext context, CallbackQuery callbackQuery)
    {
        Language defaultLang = context.languages().defaultLanguage();
        if (callbackQuery.getMessage() instanceof Message message && ("group".equals(message.getChat().getType())
                || "supergroup".equals(message.getChat().getType())))
        {
            return context.managers().languagePreferences()
                    .getChatLanguage(message.getChatId());
        }
        else if (callbackQuery.getFrom() != null)
        {
            return context.managers().languagePreferences()
                    .getUserLanguage(callbackQuery.getFrom().getId());
        }
        return defaultLang;
    }

    /**
     * Resolves the effective language for a session owner.
     *
     * @param context the per-update command context
     * @param userId the session owner's user id
     * @return the resolved user language
     */
    protected static Language sessionLanguage(HandlerContext context, long userId)
    {
        return context.managers().languagePreferences()
                .getUserLanguage(userId);
    }

    /**
     * Edits the message carrying the callback to indicate that the underlying session has expired.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @param section the translation section containing the expiry key
     * @param key the translation key for the expiry message
     * @throws TelegramApiException if the edit cannot be sent
     */
    protected static void editToExpired(HandlerContext context, CallbackQuery callbackQuery, String section, String key) throws TelegramApiException
    {
        Message message = requireMessage(callbackQuery);
        if (message == null || callbackQuery.getFrom() == null)
        {
            return;
        }

        String expiredText = context.languages().get(context.languages().defaultLanguage(),
                section, key);
        editMessage(context, message, callbackQuery, expiredText, null);
    }

    /**
     * Edits the message carrying the callback to indicate that a configuration session has expired.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @throws TelegramApiException if the edit cannot be sent
     */
    protected static void editToExpired(HandlerContext context, CallbackQuery callbackQuery) throws TelegramApiException
    {
        editToExpired(context, callbackQuery, "configuration", "session_expired");
    }

}
