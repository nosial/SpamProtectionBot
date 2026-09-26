package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.notifications.NotificationSender;
import net.nosial.spb.classes.notifications.ReportActionCallback;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportActionState;
import net.nosial.spb.utilities.ModerationActions;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Handles the moderation buttons on a report notification.
 *
 * <p>When a report is submitted every eligible moderator is sent a copy of it with Delete, Mute
 * and Ban buttons. This is what answers a press: it checks the moderator still holds the right the
 * action needs, applies the action to the reported message and its author, and removes the
 * buttons from every copy of the notification.
 *
 * <p>The buttons carry the reported message themselves (see {@link ReportActionCallback}), so a
 * moderator can act hours later or after a bot restart. The notification text is never replaced.
 *
 * <p>Separate from {@link ReportHandler} because it is a different conversation with a different
 * person. {@link ReportHandler} talks to the member filing a report; this talks to the moderators
 * who received it, possibly hours later and in another chat.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = {ReportActionCallback.PREFIX + ":", ReportActionCallback.LEGACY_PREFIX + ":"})
public final class ReportActionHandler extends Handler
{
    /** How long the "Delete + Mute 1h" action restricts the author for. */
    private static final long MUTE_DURATION_SECONDS = 3_600L;

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        CallbackQuery callbackQuery = context.callbackQuery();
        if (callbackQuery == null || callbackQuery.getData() == null || callbackQuery.getFrom() == null)
        {
            // Nothing to acknowledge: the annotation only routes callback queries here, so this
            // is unreachable in practice and must not dereference a missing query if it is not.
            return;
        }

        Language lang = resolveLanguage(context, callbackQuery);
        Message notification = requireMessage(callbackQuery);
        ReportActionCallback request = ReportActionCallback.parse(callbackQuery.getData());
        if (request == null)
        {
            if (callbackQuery.getData().startsWith(ReportActionCallback.LEGACY_PREFIX + ":"))
            {
                // Buttons sent before the actions became stateless referenced state that is gone,
                // and do not carry enough to act on. The notification text is left as it is.
                answerAlert(context, callbackQuery, context.languages().get(lang, "report", "action_expired"));
                if (notification != null)
                {
                    removeInlineKeyboard(context, notification);
                }
                return;
            }
            answer(context, callbackQuery);
            return;
        }

        // Checked live rather than trusted from when the notification was sent, since the buttons
        // do not expire and the moderator may have been demoted since.
        refreshAdministrators(context, request.chatId());
        if (!hasAdministrator(context, request.chatId(), callbackQuery.getFrom().getId(), request.action()::permits))
        {
            answerAlert(context, callbackQuery, context.languages().get(lang, "report", "action_not_permitted"));
            return;
        }

        ReportActionState state = context.sessions().reportActions().claim(request.chatId(), request.messageId());
        if (state == null)
        {
            answerAlert(context, callbackQuery, context.languages().get(lang, "report", "already_processed"));
            if (notification != null)
            {
                removeInlineKeyboard(context, notification);
            }
            return;
        }

        boolean actionSucceeded = act(context, request);

        NotificationSender.removeActionButtons(context, state.targets());
        if (notification != null && !isTracked(state, notification))
        {
            // The pressed copy is untracked when the state expired or the bot restarted since.
            removeInlineKeyboard(context, notification);
        }

        if (actionSucceeded)
        {
            answer(context, callbackQuery, context.languages().get(lang, "report", "action_completed"));
        }
        else
        {
            answerAlert(context, callbackQuery, context.languages().get(lang, "report", "action_partial_failure"));
        }
    }

    /**
     * Applies a moderation action to the reported message and its author.
     *
     * @param context the per-update command context
     * @param request the pressed button
     * @return {@code true} when every step of the action succeeded
     */
    private static boolean act(HandlerContext context, ReportActionCallback request)
    {
        boolean deleted = ModerationActions.deleteMessage(context, request.chatId(), request.messageId());
        return switch (request.action())
        {
            case DELETE -> deleted;
            case MUTE -> deleted && ModerationActions.restrictUser(context, request.chatId(), request.authorId(),
                    ModerationActions.untilFromNow(MUTE_DURATION_SECONDS));
            case BAN -> deleted && ModerationActions.banUser(context, request.chatId(), request.authorId(), null);
        };
    }

    /**
     * Returns whether the given notification is one of the tracked copies.
     *
     * @param state the claimed report action state
     * @param notification the notification whose button was pressed
     * @return {@code true} when its buttons are removed along with the tracked copies
     */
    private static boolean isTracked(ReportActionState state, Message notification)
    {
        for (NotificationTarget target : state.targets())
        {
            if (target.chatId() == notification.getChatId() && target.messageId() == notification.getMessageId())
            {
                return true;
            }
        }
        return false;
    }
}
