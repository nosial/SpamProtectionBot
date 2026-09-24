package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.ReportSubmissionService;
import net.nosial.spb.classes.notifications.NotificationSender;
import net.nosial.spb.classes.sessions.FalsePositiveReportSessionManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.spb.objects.context.FalsePositiveReportContext;
import net.nosial.spb.objects.context.ReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import net.nosial.jfederation.enums.IncidentType;
import net.nosial.spb.objects.database.ChatConfiguration;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import java.nio.file.Files;
import java.util.Set;
import java.io.File;
import java.util.List;

/**
 * Handles the Report False Positive button on a scanning notification.
 *
 * <p>When scanning acts on a message, the moderators it notifies get a button saying the bot got
 * it wrong. Pressing it files a report against the bot's own decision, tagged so the Federation
 * server can tell it apart from a report about a member, and echoes the original content so the
 * reviewer can see what was actually flagged.
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
        FalsePositiveReportContext falsePositive = parts.length == 2 ? falsePositiveSessions(context).take(parts[1]) : null;
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
            deliverFalsePositiveReport(context, report, reportUuid, lang);
            updateNotificationWithReport(context, callback, lang, reportUuid);
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "submitted"));
        }
        else
        {
            answerAlert(context, callback, context.languages().get(lang, "false_positive", "submit_failed"));
        }
    }

    /**
     * Delivers the reported false-positive evidence to the protected chat's moderators and linked
     * channel, mirroring the {@code /report} notification layout: the offending content is shown
     * first (re-sent from the cached bytes, since the original message was already deleted during
     * scanning) and the report notification is sent as a reply to it.
     *
     * @param context the per-update command context
     * @param session the report context carrying the cached evidence and metadata
     * @param reportUuid the submitted report's UUID
     * @param lang the reporter's language
     */
    private void deliverFalsePositiveReport(HandlerContext context, ReportContext session, String reportUuid, Language lang)
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(session.chatId());
        Set<Long> destinations = NotificationSender.resolveDestinations(context, configuration);
        if (destinations.isEmpty())
        {
            return;
        }
        String html = ReportSubmissionService.notificationHtml(context.languages(), lang, context, session, reportUuid, null);
        for (long destination : destinations)
        {
            Integer replyToId = null;
            Message evidence = sendFalsePositiveEvidence(context, destination, session);
            if (evidence != null)
            {
                replyToId = evidence.getMessageId();
            }
            NotificationSender.send(context, destination, html, null, null, replyToId);
        }
    }

    /**
     * Sends a false-positive attachment to a specified Telegram user or chat by uploading the
     * cached attachment bytes. The attachment is sent with an appropriate caption either
     * derived from the attachment's filename or synthesized based on the attachment's identifier.
     * If the attachment content is empty or {@code null}, no message will be sent.
     *
     * @param context the per-update command context used to execute Telegram API calls
     * @param destination the Telegram user or chat ID to which the attachment will be sent
     * @param attachment the false-positive attachment containing the content and filename
     * @return the sent message if the operation was successful, or {@code null} otherwise
     */
    private Message sendFalsePositiveAttachment(HandlerContext context, long destination, ReportAttachment attachment)
    {
        byte[] content = attachment.content();
        if (content == null || content.length == 0)
        {
            return null;
        }

        String fileName = attachment.fileName() != null && !attachment.fileName().isBlank() ? attachment.fileName() : "telegram-" + attachment.fileId();
        File staged = null;

        try
        {
            staged = ReportSubmissionService.stageBytesToFile(content, fileName);
            return context.telegramClient().execute(SendDocument.builder()
                    .chatId(String.valueOf(destination))
                    .document(new InputFile(staged))
                    .caption(fileName)
                    .build());
        }
        catch (Exception e)
        {
            LOGGER.warn("Unable to re-send false-positive attachment {} to {}: {}", attachment.fileId(), destination, e.getMessage());
            return null;
        }
        finally
        {
            if (staged != null)
            {
                try
                {
                    Files.deleteIfExists(staged.toPath());
                }
                catch (Exception e)
                {
                    LOGGER.debug("Could not delete temporary false-positive attachment {}: {}", staged, e.getMessage());
                }
            }
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
     * Re-sends the cached false-positive evidence (the offending text and any captured attachment
     * bytes) to a single destination so it can be shown alongside the report notification. Because
     * the original message is deleted during scanning, the content is reproduced from the cached
     * report context rather than re-forwarded.
     *
     * @param context the per-update command context
     * @param destination the Telegram user or chat id to send the evidence to
     * @param session the report context carrying the cached evidence
     * @return the last message sent, or {@code null} when nothing could be delivered
     */
    private Message sendFalsePositiveEvidence(HandlerContext context, long destination, ReportContext session)
    {
        Message last = null;
        String text = session.targetText();
        boolean hasText = text != null && !text.isBlank();
        if (hasText)
        {
            try
            {
                last = context.telegramClient().execute(SendMessage.builder().chatId(String.valueOf(destination)).text(text).build());
            }
            catch (TelegramApiException e)
            {
                LOGGER.warn("Unable to re-send false-positive text to {}: {}", destination, e.getMessage());
            }
        }
        if (session.attachments() != null)
        {
            for (ReportAttachment attachment : session.attachments())
            {
                Message sent = sendFalsePositiveAttachment(context, destination, attachment);
                if (sent != null)
                {
                    last = sent;
                }
            }
        }
        return last;
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
