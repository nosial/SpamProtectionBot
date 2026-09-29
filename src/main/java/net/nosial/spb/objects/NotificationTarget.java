package net.nosial.spb.objects;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * One delivered copy of a report notification that carries moderation buttons, recorded so the
 * buttons can be removed from every copy once a moderator acts.
 *
 * @param chatId the chat the notification was delivered to (usually a moderator's private chat)
 * @param messageId the notification message carrying the buttons
 * @param retainedMarkup the keyboard left on the notification once its moderation buttons are
 *                       removed, such as its link to the Federation Web Application, or
 *                       {@code null} to leave no keyboard
 */
public record NotificationTarget(long chatId, int messageId, InlineKeyboardMarkup retainedMarkup)
{
    /**
     * Records a notification whose keyboard is removed entirely once a moderator acts.
     *
     * @param chatId the chat the notification was delivered to
     * @param messageId the notification message carrying the buttons
     */
    public NotificationTarget(long chatId, int messageId)
    {
        this(chatId, messageId, null);
    }
}
