package net.nosial.spb.handlers.group;

import net.nosial.spb.utilities.MessageHelper;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.objects.Language;
import net.nosial.spb.handlers.secretary.SecretarySettingsHandler;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.context.ConfigurationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * The {@code /settings} command handler.
 *
 * <p>In group chats, sends the configuration menu as an ephemeral message visible only to the
 * sender. The sender must be an administrator with the ability to change group information, and
 * the bot must have the required permissions.
 *
 * <p>In private chats, opens the configuration menu in the user's private chat. When called
 * without arguments the full menu is shown. When called with {@code private}, {@code p}, or
 * {@code dm} as an argument, the same private menu is opened (useful when the user types the
 * command directly instead of clicking a button).
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "settings")
public final class SettingsHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsHandler.class);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        switch (message.getChat().getType())
        {
            case "group":
            case "supergroup":
                handleGroupSettings(context, message);
                break;

            case "private":
                handlePrivateSettings(context, message);
                break;
        }
    }

    /**
     * Handles {@code /settings} in a group chat.
     *
     * <p>Only administrators with the ability to change group information are answered. The
     * settings menu is sent as an ephemeral message visible only to the sender. The bot must be
     * a change-information administrator for the menu to open. Both permissions are checked
     * against Telegram's current administrator list, not a cached one.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /settings} message
     * @throws TelegramApiException if a reply cannot be sent
     */
    private static void handleGroupSettings(HandlerContext context, Message message) throws TelegramApiException
    {
        // Checked against Telegram rather than the cache, so a permission just granted or revoked
        // applies to this very command instead of after the cached list expires.
        refreshAdministrators(context, message.getChatId());

        if (!isChangeInformationAdministrator(context, message))
        {
            LOGGER.debug("Ignoring /settings from non-administrator {} in chat {}",
                    message.getFrom(), message.getChatId());
            return;
        }

        if (!isBotChangeInformationAdministrator(context, message.getChatId()))
        {
            Language lang = resolveLanguage(context, message);
            context.telegramClient().execute(SendMessage.builder()
                    .chatId(String.valueOf(message.getChatId()))
                    .replyToMessageId(message.getMessageId())
                    .messageThreadId(MessageHelper.topicId(message))
                    .receiverUserId(message.getFrom().getId())
                    .text(context.languages().get(lang, "settings", "bot_admin_required"))
                    .parseMode(ParseMode.HTML)
                    .build());
            return;
        }

        ConfigurationContext session = context.sessions().configuration().createEphemeral(message.getFrom().getId(), message.getChatId());
        ConfigurationHandler.openMainMenu(context, session);
    }

    /**
     * Handles {@code /settings} in a private chat.
     *
     * <p>Opens the configuration menu in the user's private chat. When called with an argument
     * ({@code private}, {@code p}, or {@code dm}) the same menu is opened. Without arguments
     * the full menu is also shown.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /settings} message
     * @throws TelegramApiException if a reply cannot be sent
     */
    private static void handlePrivateSettings(HandlerContext context, Message message) throws TelegramApiException
    {
        if (context.managers().secretaryConfigurations().secretaryConfigurationExists(message.getFrom().getId()))
        {
            ConfigurationContext session = context.sessions().configuration().create(message.getFrom().getId(), message.getChatId());
            SecretarySettingsHandler.openMenu(context, session);
            return;
        }
        Language lang = resolveLanguage(context, message);
        sendReply(context, message, context.languages().get(lang, "settings", "secretary_not_enabled"));
    }
}
