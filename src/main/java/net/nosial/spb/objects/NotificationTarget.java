package net.nosial.spb.objects;

/**
 * One delivered copy of a report notification that carries moderation buttons, recorded so the
 * buttons can be removed from every copy once a moderator acts.
 *
 * @param chatId the chat the notification was delivered to (usually a moderator's private chat)
 * @param messageId the notification message carrying the buttons
 */
public record NotificationTarget(long chatId, int messageId)
{
}