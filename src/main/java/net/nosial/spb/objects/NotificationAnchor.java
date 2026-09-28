package net.nosial.spb.objects;

/**
 * One notification destination prepared ahead of a moderation action, holding the copy of the
 * offending message forwarded there so the notification sent afterwards can reply to it.
 *
 * <p>The message is forwarded before the action because Telegram cannot forward a message once it
 * has been deleted.
 *
 * @param destination the chat or user the notification goes to
 * @param messageThreadId the forum topic to post in, or {@code null}
 * @param replyToMessageId the forwarded copy to reply to, or {@code null} when it could not be forwarded
 */
public record NotificationAnchor(long destination, Integer messageThreadId, Integer replyToMessageId)
{
}
