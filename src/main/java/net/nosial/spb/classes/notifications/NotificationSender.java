package net.nosial.spb.classes.notifications;

import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Delivery layer for moderation notifications.
 *
 * <p>Resolves notification destinations from a chat's configuration and delivers HTML messages
 * with optional inline keyboards. Report notifications additionally forward the reported message
 * to each destination, reply to it with per-admin action buttons, and return the delivered
 * {@link NotificationTarget}s so the buttons can be removed once a moderator acts. Individual
 * delivery failures are isolated — a failure for one destination never prevents delivery to
 * others.
 *
 * <p>Message content is built by {@link NotificationFormatter}. This class is stateless and
 * thread-safe.
 */
public final class NotificationSender
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationSender.class);

    private NotificationSender()
    {
    }

    /**
     * Delivers an HTML moderation notification according to the protected chat's settings.
     *
     * @param context current update context
     * @param protectedChatId chat where the moderation event occurred
     * @param html Telegram HTML notification text
     */
    public static void notify(HandlerContext context, long protectedChatId, String html)
    {
        notify(context, protectedChatId, html, null);
    }

    /**
     * Delivers an HTML moderation notification with an optional action keyboard to every
     * destination resolved for the protected chat. Messages to the linked chat are posted in its
     * configured topic, when one is set.
     *
     * @param context current update context
     * @param protectedChatId chat where the moderation event occurred
     * @param html Telegram HTML notification text
     * @param markup optional inline action keyboard
     */
    public static void notify(HandlerContext context, long protectedChatId, String html, InlineKeyboardMarkup markup)
    {
        if (html == null || html.isBlank())
        {
            return;
        }

        ChatConfiguration configuration = resolveConfiguration(context, protectedChatId);
        Long channelLinkId = configuration.channelLinkId();
        for (long destination : resolveDestinations(context, configuration))
        {
            Integer threadId = channelLinkId != null && destination == channelLinkId
                    && configuration.channelLinkThreadId() != null ? configuration.channelLinkThreadId().intValue() : null;
            send(context, destination, html, markup, threadId, null);
        }
    }

    /**
     * Resolves the set of Telegram user/chat ids that should receive moderation notifications
     * for the given protected chat.
     *
     * <p>Includes the linked channel (if configured) and all cached administrators (when
     * moderator notifications are enabled). The bot's own user id is always excluded.
     *
     * @param context current update context
     * @param configuration the chat's configuration
     * @return the set of destination ids, possibly empty
     */
    public static Set<Long> resolveDestinations(HandlerContext context, ChatConfiguration configuration)
    {
        Set<Long> destinations = new LinkedHashSet<>();
        if (configuration.channelLinkId() != null)
        {
            destinations.add(configuration.channelLinkId());
        }

        if (configuration.moderatorNotificationsEnabled())
        {
            List<AdminInfo> admins = context.chatAdmins().getIfPresent(configuration.chatId());
            if (admins != null)
            {
                for (AdminInfo admin : admins)
                {
                    if (admin.isModerator())
                    {
                        destinations.add(admin.id());
                    }
                }
            }
            destinations.remove(context.botUserId());
        }

        return destinations;
    }

    /**
     * Delivers a report notification by forwarding the reported message first, then sending the
     * report details as a reply to the forwarded message with action buttons tailored to each
     * admin's permissions. The linked chat receives the same notification without buttons.
     *
     * @param context current update context
     * @param protectedChatId chat where the reported message lives
     * @param reportHtml the report notification HTML (without the content block)
     * @param reportUuid the report UUID
     * @param targetMessageId the original message id in the protected chat
     * @param targetAuthorId the original author id of the reported message
     * @param linkedChatNotification whether the linked chat/channel should also receive the notification
     * @return the list of notification targets for tracking button removal
     */
    public static List<NotificationTarget> notifyReportSubmitted(HandlerContext context, long protectedChatId,
                                                                  String reportHtml, String reportUuid,
                                                                  long targetMessageId, long targetAuthorId,
                                                                  boolean linkedChatNotification)
    {
        ChatConfiguration configuration = resolveConfiguration(context, protectedChatId);
        List<AdminInfo> admins = context.chatAdmins().getIfPresent(protectedChatId);
        List<NotificationTarget> targets = new ArrayList<>();

        if (configuration.moderatorNotificationsEnabled() && admins != null)
        {
            AdminInfo botInfo = findAdmin(admins, context.botUserId());
            Set<Long> moderators = new LinkedHashSet<>();
            for (AdminInfo admin : admins)
            {
                if (admin.id() != context.botUserId() && admin.isModerator())
                {
                    moderators.add(admin.id());
                }
            }
            for (long moderator : moderators)
            {
                AdminInfo adminInfo = findAdmin(admins, moderator);
                InlineKeyboardMarkup markup = adminInfo != null
                        ? buildActionButtons(adminInfo, botInfo, reportUuid) : null;
                deliverReport(context, protectedChatId, moderator, reportHtml, markup,
                        targetMessageId, targetAuthorId, targets);
            }
        }

        if (linkedChatNotification && configuration.channelLinkId() != null)
        {
            deliverReport(context, protectedChatId, configuration.channelLinkId(), reportHtml, null,
                    targetMessageId, targetAuthorId, targets);
        }
        return targets;
    }

    /**
     * Removes the inline action keyboard from all notification messages for a given report.
     *
     * @param context current update context
     * @param targets the notification targets to strip keyboards from
     */
    public static void removeActionButtons(HandlerContext context, List<NotificationTarget> targets)
    {
        for (NotificationTarget target : targets)
        {
            try
            {
                context.telegramClient().execute(EditMessageReplyMarkup.builder()
                        .chatId(String.valueOf(target.notificationChatId()))
                        .messageId(target.notificationMessageId())
                        .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of()).build())
                        .build());
            }
            catch (TelegramApiException e)
            {
                LOGGER.debug("Could not remove action buttons from notification {} in chat {}: {}",
                        target.notificationMessageId(), target.notificationChatId(), e.getMessage());
            }
        }
    }

    /**
     * Sends an HTML message to a single destination.
     *
     * @param context current update context
     * @param destination the Telegram user or chat id to send to
     * @param html the HTML message text
     * @param markup optional inline keyboard
     * @param messageThreadId optional forum topic to post in
     * @param replyToMessageId optional message to reply to
     * @return the sent message, or {@code null} on failure
     */
    public static Message send(HandlerContext context, long destination, String html, InlineKeyboardMarkup markup,
                               Integer messageThreadId, Integer replyToMessageId)
    {
        try
        {
            var request = SendMessage.builder()
                    .chatId(String.valueOf(destination))
                    .text(html)
                    .parseMode(ParseMode.HTML);
            if (messageThreadId != null)
            {
                request.messageThreadId(messageThreadId);
            }
            if (replyToMessageId != null)
            {
                request.replyToMessageId(replyToMessageId);
            }
            if (markup != null)
            {
                request.replyMarkup(markup);
            }
            return context.telegramClient().execute(request.build());
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to send notification to {}: {}", destination, e.getMessage());
            return null;
        }
    }

    /**
     * Forwards a message between chats.
     *
     * @param context current update context
     * @param fromChatId the chat the message lives in
     * @param toChatId the chat to forward it to
     * @param messageId the message to forward
     * @return the forwarded message, or {@code null} on failure
     */
    public static Message forward(HandlerContext context, long fromChatId, long toChatId, int messageId)
    {
        try
        {
            return context.telegramClient().execute(ForwardMessage.builder()
                    .chatId(String.valueOf(toChatId))
                    .fromChatId(String.valueOf(fromChatId))
                    .messageId(messageId)
                    .build());
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to forward message {} from chat {} to chat {}: {}",
                    messageId, fromChatId, toChatId, e.getMessage());
            return null;
        }
    }

    /**
     * Forwards the reported message to one destination and replies to it with the report details,
     * recording the delivered notification in {@code targets}.
     */
    private static void deliverReport(HandlerContext context, long protectedChatId, long destination,
                                      String reportHtml, InlineKeyboardMarkup markup, long targetMessageId,
                                      long targetAuthorId, List<NotificationTarget> targets)
    {
        Message forwarded = forward(context, protectedChatId, destination, (int) targetMessageId);
        Integer replyToId = forwarded != null ? forwarded.getMessageId() : null;

        Message sent = send(context, destination, reportHtml, markup, null, replyToId);
        if (sent != null)
        {
            targets.add(new NotificationTarget(protectedChatId, destination, sent.getMessageId(),
                    targetMessageId, targetAuthorId));
        }
    }

    /**
     * Builds action buttons based on the intersection of the admin's permissions and the bot's
     * own permissions. A button is only offered when both can perform the underlying moderation
     * action, since the bot executes it.
     *
     * @param adminInfo the receiving administrator's cached permissions
     * @param botInfo the bot's own cached permissions, or {@code null} when unknown
     * @param reportUuid the report UUID for callback data
     * @return the inline keyboard, or {@code null} when no combined permissions apply
     */
    private static InlineKeyboardMarkup buildActionButtons(AdminInfo adminInfo, AdminInfo botInfo, String reportUuid)
    {
        List<InlineKeyboardButton> buttons = new ArrayList<>();

        boolean botCanDelete = botInfo != null && botInfo.canDeleteMessages();
        boolean botCanRestrict = botInfo != null && botInfo.canRestrictMembers();

        if (adminInfo.canDeleteMessages() && botCanDelete)
        {
            buttons.add(InlineKeyboardButton.builder()
                    .text("Delete Message")
                    .callbackData("report_action:" + reportUuid + ":delete")
                    .build());
        }
        if (adminInfo.canRestrictMembers() && botCanRestrict)
        {
            buttons.add(InlineKeyboardButton.builder()
                    .text("Delete + Mute 1h")
                    .callbackData("report_action:" + reportUuid + ":mute")
                    .build());
            buttons.add(InlineKeyboardButton.builder()
                    .text("Delete + Ban")
                    .callbackData("report_action:" + reportUuid + ":ban")
                    .build());
        }

        if (buttons.isEmpty())
        {
            return null;
        }
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(buttons)).build();
    }

    /**
     * Resolves the configuration for a specific chat.
     *
     * @param context the current handling context containing managers and utilities
     * @param chatId the unique identifier of the chat whose configuration is to be resolved
     * @return the {@code ChatConfiguration} associated with the specified chat
     */
    private static ChatConfiguration resolveConfiguration(HandlerContext context, long chatId)
    {
        return context.managers().chatConfigurations().resolve(chatId);
    }

    /**
     * Searches the provided list of administrators to find an administrator with the specified ID.
     *
     * @param admins the list of administrators to search, may be {@code null}
     * @param id the unique identifier of the administrator to find
     * @return the matching {@code AdminInfo} instance if found, or {@code null} if no match is found
     */
    private static AdminInfo findAdmin(List<AdminInfo> admins, long id)
    {
        if (admins == null)
        {
            return null;
        }
        for (AdminInfo admin : admins)
        {
            if (admin.id() == id)
            {
                return admin;
            }
        }
        return null;
    }
}
