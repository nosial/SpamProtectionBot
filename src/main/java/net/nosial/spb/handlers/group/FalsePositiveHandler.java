package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.ReportSubmissionService;
import net.nosial.spb.classes.sessions.FalsePositiveReportSessionManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.context.FalsePositiveReportContext;
import net.nosial.spb.objects.context.ReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import net.nosial.jfederation.enums.IncidentType;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import java.util.List;

/**
 * Handles the Report False Positive button on a scanning notification.
 *
 * <p>When scanning acts on a message, the moderators it notifies get a button saying the bot got
 * it wrong. Pressing it files a report against the bot's own decision, tagged so the Federation
 * server can tell it apart from a report about a member, and records the report on the notification
 * the moderator pressed it on. Moderators pressing it on their copies afterwards are told the report
 * was already submitted.
 *
 * <p>Separate from {@link ScanningHandler}, which decides and notifies, and from
 * {@link ReportHandler}, which serves members reporting each other: this is the one flow where the
 * subject of the report is the bot.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = FalsePositiveReportSessionManager.CALLBACK_PREFIX + ":")
public final class FalsePositiveHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FalsePositiveHandler.class);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        CallbackQuery callback = context.callbackQuery();
        if (callback == null || callback.getData() == null || callback.getFrom() == null)
        {
            // Nothing to acknowledge: the annotation only routes callback queries here, so this
            // is unreachable in practice and must not dereference a missing query if it is not.
            return;
        }

        report(context, callback, callback.getData());
    }

    /**
     * Consumes the one-time Report False Positive action attached to a scanning notification.
     *
     * @param context the per-update command context
     * @param callback the incoming callback query
     * @param data the full callback data string
     * @throws TelegramApiException if the answer cannot be sent
     */
    private void report(HandlerContext context, CallbackQuery callback, String data) throws TelegramApiException
    {
        Language lang = resolveLanguage(context, callback);
        String[] parts = data.split(":", 2);
        String hash = parts.length == 2 ? parts[1] : null;
        FalsePositiveReportContext pending = hash != null ? falsePositiveSessions(context).find(hash) : null;
        if (pending == null)
        {
            // The session held the deleted message's content, so the action cannot be recovered.
            // Only the button goes; the notification stays readable.
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "expired"));
            if (callback.getMessage() instanceof Message notification)
            {
                removeInlineKeyboard(context, notification);
            }
            return;
        }

        // The notification can also reach a linked channel or group whose members are not
        // moderators of the protected chat, so the presser is checked before the one-time action
        // is used up.
        refreshAdministrators(context, pending.chatId());
        if (!isChatAdministrator(context, pending.chatId(), callback.getFrom().getId()))
        {
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "not_permitted"));
            return;
        }

        // Every copy of the notification carries the same action, so a moderator pressing it after
        // another has submitted the report is only told so.
        if (pending.reportUuid() != null)
        {
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "already_submitted"));
            return;
        }

        FalsePositiveReportContext falsePositive = falsePositiveSessions(context).take(hash);
        if (falsePositive == null)
        {
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "expired"));
            return;
        }

        ReportContext report = new ReportContext(null, callback.getFrom().getId(), falsePositive.chatId(),
                falsePositive.messageId(), falsePositive.authorId(), falsePositive.text(), falsePositive.attachments(),
                falsePositive.metadata(), null, false, null, null, IncidentType.OTHER, true, System.currentTimeMillis(),
                System.currentTimeMillis());
        String reportUuid = ReportSubmissionService.submitFalsePositive(context, report);
        if (reportUuid != null)
        {
            falsePositiveSessions(context).markSubmitted(falsePositive, reportUuid);
            updateNotificationWithReport(context, callback, lang, reportUuid);
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "submitted"));
        }
        else
        {
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "submit_failed"));
        }
    }

    /**
     * Updates the scanning notification that carried the "Report False Positive" button so it
     * records the submitted report's identifier and clears the action button.
     *
     * @param context the per-update command context
     * @param callback the callback query whose message carries the notification
     * @param lang the responder's language
     * @param reportUuid the submitted report's UUID
     */
    private static void updateNotificationWithReport(HandlerContext context, CallbackQuery callback, Language lang, String reportUuid)
    {
        MaybeInaccessibleMessage notification = callback.getMessage();
        if (notification == null || notification.getMessageId() == null)
        {
            return;
        }
        String original = (notification instanceof Message message) ? message.getText() : null;
        String reportInfo = context.languages().get(lang, "false_positive", "submitted_block", reportUuid);
        String newText = (original == null || original.isBlank() ? "" : original + "\n\n") + reportInfo;
        try
        {
            context.telegramClient().execute(EditMessageText.builder()
                    .chatId(notification.getChatId())
                    .messageId(notification.getMessageId())
                    .text(newText)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of()).build())
                    .build());
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Failed to update false-positive notification message {} in chat {}: {}", notification.getMessageId(), notification.getChatId(), e.getMessage());
        }
    }

    /**
     * Returns the one-shot false-positive actions currently outstanding.
     *
     * @param context the per-update context
     * @return the false-positive session manager
     */
    private static FalsePositiveReportSessionManager falsePositiveSessions(HandlerContext context)
    {
        return context.sessions().falsePositive();
    }
}
