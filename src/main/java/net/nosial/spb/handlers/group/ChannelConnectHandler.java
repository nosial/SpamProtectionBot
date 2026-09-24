package net.nosial.spb.handlers.group;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.classes.sessions.ConfigurationSessionManager;
import net.nosial.spb.objects.context.ConfigurationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;
import java.util.List;

/**
 * Handles the {@code /connect <id>} command used to link a channel or group for notifications.
 *
 * <p>The command may be sent directly in a channel, in a linked discussion group as an automatic
 * channel forward, or by an administrator in a target group. The id is matched against the
 * verification code stored while an administrator is viewing the Chat Linking settings page. On a
 * match the target chat is linked to the protected chat, the verification code is cleared, the
 * original command message is deleted when possible, and the private configuration menu updates.
 */
@UpdateHandler(value = {UpdateType.COMMAND, UpdateType.CHANNEL_POST}, commands = "connect")
public final class ChannelConnectHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelConnectHandler.class);

    @Override
    public boolean accepts(HandlerContext context)
    {
        // A channel post is not a command message, so the annotation's command filter cannot see
        // it; /connect is posted in the target channel itself, which is the whole point of it.
        Message message = incomingMessage(context.update());
        if (message == null || message.getText() == null)
        {
            return false;
        }

        String command = message.getText().trim().split("\\s+")[0];
        return command.equals("/connect") || command.startsWith("/connect@");
    }

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = incomingMessage(context.update());
        if (message == null)
        {
            return;
        }

        Language lang = resolveLanguage(context, message);
        Long channelId = resolveLinkedChatId(context, message);
        if (channelId == null)
        {
            LOGGER.debug("Ignoring /connect outside an eligible target chat");
            return;
        }

        String token = singleArgument(message);
        if (token == null)
        {
            sendReply(context, message, context.languages().get(lang, "channel_connect", "usage"));
            return;
        }

        long verificationCode;
        try
        {
            verificationCode = Long.parseLong(token);
        }
        catch (NumberFormatException e)
        {
            sendReply(context, message, context.languages().get(lang, "channel_connect", "invalid_id"));
            return;
        }

        Optional<ChatConfiguration> configuration =
                context.managers().chatConfigurations().getChatConfigurationByChannelLinkVerificationCode(verificationCode);
        if (configuration.isEmpty())
        {
            if (context.managers().chatConfigurations().getChatConfigurationByChannelLinkId(channelId).isPresent())
            {
                LOGGER.debug("Notification chat {} is already linked; ignoring duplicate /connect", channelId);
                return;
            }
            sendReply(context, message, context.languages().get(lang, "channel_connect", "unknown_id"));
            return;
        }

        long chatId = configuration.get().chatId();
        try
        {
            context.managers().chatConfigurations().linkChannel(chatId, channelId,
                    message.getMessageThreadId() != null ? (long) message.getMessageThreadId() : null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to link notification chat {} to chat {}: {}", channelId, chatId, e.getMessage());
            sendReply(context, message, context.languages().get(lang, "channel_connect", "save_failed"));
            return;
        }
        sendReply(context, message, context.languages().get(lang, "channel_connect", "linked_success", chatId));
        deleteCommandMessage(context, message);
        refreshConfigurationMenu(context, verificationCode, chatId);
    }

    /**
     * Updates the configuration menu that displayed the verification code, so the administrator
     * sees the chat linked without having to reopen the settings.
     *
     * <p>The link is already saved and confirmed by the time this runs; failing to edit the menu
     * (it may have been deleted, or the session expired) is logged and otherwise ignored.
     *
     * @param context the per-update context
     * @param verificationCode the code that was just redeemed
     * @param chatId the protected chat the notification chat was linked to
     */
    private static void refreshConfigurationMenu(HandlerContext context, long verificationCode, long chatId)
    {
        ConfigurationSessionManager sessions = context.sessions().configuration();
        ConfigurationContext session = sessions.findByChannelLinkVerificationCode(verificationCode);
        if (session == null || session.chatId() != chatId)
        {
            return;
        }

        // The code has been used; it must not be redeemable again from the same menu.
        sessions.updateChannelLinkVerificationCode(session.hash(), null);
        boolean hasMenu = session.ephemeral() ? session.ephemeralMessageId() != null : session.messageId() != null;
        if (!hasMenu)
        {
            return;
        }

        try
        {
            ConfigurationHandler.notifyChannelLinked(context, sessions.find(session.hash()));
        }
        catch (TelegramApiException e)
        {
            LOGGER.debug("Could not refresh the configuration menu for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Returns the message from either a regular message update or a channel post update.
     *
     * @param update the incoming update
     * @return the message, or {@code null}
     */
    private static Message incomingMessage(Update update)
    {
        if (update.hasMessage())
        {
            return update.getMessage();
        }
        if (update.hasChannelPost())
        {
            return update.getChannelPost();
        }
        return null;
    }

    /**
     * Resolves a notification target that is either a channel or a group whose sender is an
     * eligible administrator.
     *
     * @param context the per-update command context
     * @param message the command message
     * @return the target chat id, or {@code null} when the sender cannot link it
     */
    static Long resolveLinkedChatId(HandlerContext context, Message message)
    {
        String chatType = message.getChat().getType();

        if ("channel".equals(chatType))
        {
            return message.getChatId();
        }

        if (("group".equals(chatType) || "supergroup".equals(chatType)) && Boolean.TRUE.equals(message.getIsAutomaticForward()))
        {
            if (message.getSenderChat() != null && "channel".equals(message.getSenderChat().getType()))
            {
                return message.getSenderChat().getId();
            }
            if (message.getForwardFromChat() != null && "channel".equals(message.getForwardFromChat().getType()))
            {
                return message.getForwardFromChat().getId();
            }
        }

        if (("group".equals(chatType) || "supergroup".equals(chatType)) && message.getFrom() != null)
        {
            List<AdminInfo> administrators = context.chatAdmins().getIfPresent(message.getChatId());
            if (administrators != null && administrators.stream().anyMatch(a -> a.id() == message.getFrom().getId()))
            {
                return message.getChatId();
            }
        }

        return null;
    }
}
