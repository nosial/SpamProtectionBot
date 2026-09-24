package net.nosial.spb.objects;

/**
 * Tracks a forwarded message and its report notification message for one notification recipient.
 *
 * @param chatId the chat where the reported message lives
 * @param notificationChatId the chat where the notification was delivered (often a private admin chat)
 * @param notificationMessageId the message id of the report notification with action buttons
 * @param targetMessageId the original message id in the protected chat
 * @param targetAuthorId the original author id of the reported message
 */
public record NotificationTarget(long chatId, long notificationChatId, int notificationMessageId,
                                 long targetMessageId, long targetAuthorId)
{
}