package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportActionState;
import net.nosial.spb.utilities.ModerationActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import net.nosial.spb.classes.notifications.NotificationSender;

/**
 * Handles the moderation buttons on a report notification.
 *
 * <p>When a report is submitted every eligible moderator is sent a copy of it with Delete, Mute
 * and Ban buttons. This is what answers a press: it resolves the action, applies it to the
 * reported message and its author, and removes the buttons from every copy of the notification.
 *
 * <p>Separate from {@link ReportHandler} because it is a different conversation with a different
 * person. {@link ReportHandler} talks to the member filing a report; this talks to the moderators
 * who received it, possibly hours later and in another chat. Only the callback namespace
 * {@code report_action:} connects them.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = ReportActionHandler.CALLBACK_PREFIX + ":")
public final class ReportActionHandler extends Handler
{
    /** How long the "Delete + Mute 1h" action restricts the author for. */
    private static final long MUTE_DURATION_SECONDS = 3_600L;

    /**
     * The callback namespace for the buttons on a report notification.
     *
     * <p>Public because whatever delivers the notification builds the buttons.
     */
    public static final String CALLBACK_PREFIX = "report_action";

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

        act(context, callbackQuery, callbackQuery.getData());
    }

    /**
     * Handles a report action callback: delete message, delete + mute, or delete + ban.
     * After executing the action, removes action buttons from all notification copies.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @param data the full callback data string
     * @throws TelegramApiException if the action or edit cannot be sent
     */
    private static void act(HandlerContext context, CallbackQuery callbackQuery, String data) throws TelegramApiException
    {
        String[] parts = data.split(":", 3);
        if (parts.length != 3)
        {
            // Nothing to acknowledge: the annotation only routes callback queries here, so this
            // is unreachable in practice and must not dereference a missing query if it is not.
            return;
        }

        String reportUuid = parts[1];
        String action = parts[2];

        Language lang = resolveLanguage(context, callbackQuery);
        ReportActionState state = (ReportActionState) context.cache().getIfPresent(
                "report_notifications:" + reportUuid);
        if (state == null || state.targets().isEmpty() || !state.claim())
        {
            answerAlert(context, callbackQuery,
                    context.languages().get(lang, "report", "already_processed"));
            return;
        }

        List<NotificationTarget> targets = state.targets();
        NotificationTarget firstTarget = targets.get(0);
        long chatId = firstTarget.chatId();
        long targetMessageId = firstTarget.targetMessageId();
        long targetAuthorId = firstTarget.targetAuthorId();

        boolean actionSucceeded;
        switch (action)
        {
            case "delete" ->
            {
                actionSucceeded = ModerationActions.deleteMessage(context, chatId, (int) targetMessageId);
            }
            case "mute" ->
                    actionSucceeded = ModerationActions.deleteMessage(context, chatId, (int) targetMessageId)
                            && ModerationActions.restrictUser(context, chatId, targetAuthorId,
                                    ModerationActions.untilFromNow(MUTE_DURATION_SECONDS));
            case "ban" ->
                    actionSucceeded = ModerationActions.deleteMessage(context, chatId, (int) targetMessageId)
                            && ModerationActions.banUser(context, chatId, targetAuthorId, null);
            default ->
            {
                answer(context, callbackQuery);
                return;
            }
        }

        NotificationSender.removeActionButtons(context, targets);
        context.cache().remove("report_notifications:" + reportUuid);

        if (actionSucceeded)
        {
            answer(context, callbackQuery,
                    context.languages().get(lang, "report", "action_completed"));
        }
        else
        {
            answerAlert(context, callbackQuery,
                    context.languages().get(lang, "report", "action_partial_failure"));
        }
    }
}
