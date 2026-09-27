package net.nosial.spb.handlers.operators;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.ClassificationFlag;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.notifications.OperatorReportCallback;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the action buttons on report-notification detail messages.
 *
 * <p>The buttons carry the report UUID themselves (see {@link OperatorReportCallback}), so they do
 * not expire and survive bot restarts. The report is closed with the credential currently stored
 * for the clicking Telegram user, in a short-lived Federation client, so the callback never
 * changes the shared client authentication used by update handlers. Federation decides whether
 * that operator may close the report.
 *
 * <p>The notification text is never rewritten, so the report UUID and details stay available to
 * the operator: a successful close removes the buttons and posts the result as a reply, and a
 * failure is only reported in an alert, leaving the buttons in place for another attempt.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = {OperatorReportCallback.PREFIX + ":", OperatorReportCallback.LEGACY_PREFIX + ":"})
public final class OperatorReportHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorReportHandler.class);
    private static final String CLAIM_KEY_PREFIX = "operator_report_close:";
    private static final String LEGACY_CLASSIFICATION_PREFIX = "class:";
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        CallbackQuery callback = context.update().getCallbackQuery();
        Message message = callback.getMessage() instanceof Message regularMessage ? regularMessage : null;
        if (callback.getFrom() == null || message == null)
        {
            answer(context, callback);
            return;
        }

        Language lang = resolveLanguage(context, callback);
        String[] parts = callback.getData().split(":", 3);
        if (parts.length != 3)
        {
            answer(context, callback);
            return;
        }

        // Notifications are only delivered to the operator's private chat with the bot.
        if (!callback.getFrom().getId().equals(message.getChatId()))
        {
            answer(context, callback);
            return;
        }

        String reportUuid;
        String action;
        if (OperatorReportCallback.LEGACY_PREFIX.equals(parts[0]))
        {
            // Buttons sent before the actions became stateless referenced an in-memory session that
            // no longer exists, but the notification itself still shows the report UUID.
            reportUuid = notificationReportUuid(message);
            action = parts[2].startsWith(LEGACY_CLASSIFICATION_PREFIX)
                    ? parts[2].substring(LEGACY_CLASSIFICATION_PREFIX.length())
                    : parts[2];
            if (reportUuid == null)
            {
                answerAlert(context, callback, context.languages().get(lang, "operator_report", "expired_alert"));
                return;
            }
        }
        else
        {
            reportUuid = parts[1];
            action = parts[2];
        }

        ClassificationFlag classification = classification(action);
        if (!MessageHelper.isUuid(reportUuid) || (!OperatorReportCallback.CLOSE.equals(action) && classification == null))
        {
            answer(context, callback);
            return;
        }

        OperatorIdentity operator = context.managers().operators().getOperator(callback.getFrom().getId()).orElse(null);
        if (operator == null)
        {
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "not_operator"));
            return;
        }

        if (!context.federation().isAvailable())
        {
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "federation_not_configured"));
            return;
        }

        // Guards against Telegram delivering the same press twice, or a double tap, sending two
        // close requests; the second would be rejected after the first had closed the report.
        String claimKey = CLAIM_KEY_PREFIX + callback.getFrom().getId() + ":" + reportUuid;
        AtomicBoolean claim = (AtomicBoolean) context.cache().get(claimKey, ignored -> new AtomicBoolean(false));
        if (!claim.compareAndSet(false, true))
        {
            answer(context, callback);
            return;
        }

        try
        {
            context.federation().closeReport(operator.accessToken(), reportUuid, classification);
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to close report {} for Telegram operator {}: {}", reportUuid, callback.getFrom().getId(), e.getMessage());
            context.cache().remove(claimKey);
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "federation_rejected_close"));
            return;
        }

        answer(context, callback, context.languages().get(lang, "notifications", "report.closed"));
        replyClosed(context, message, lang, reportUuid, classification);
    }

    /**
     * Parses the classification flag named by a button action.
     *
     * @param action the action part of the callback data
     * @return the named {@code ClassificationFlag}, or {@code null} when the action is not one
     */
    private static ClassificationFlag classification(String action)
    {
        try
        {
            return ClassificationFlag.valueOf(action);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Reads the report UUID from a notification's text, where it is the first UUID shown.
     *
     * @param message the report notification
     * @return the report UUID, or {@code null} when the text does not contain one
     */
    private static String notificationReportUuid(Message message)
    {
        if (message.getText() == null)
        {
            return null;
        }
        Matcher matcher = UUID_PATTERN.matcher(message.getText());
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Marks a notification as handled: removes its action buttons and replies to it with the
     * outcome, leaving the notification text itself intact.
     *
     * @param context the handler context providing necessary services and utilities
     * @param message the report notification
     * @param lang the language of the reply
     * @param reportUuid the UUID of the closed report
     * @param classification the classification the report was closed with, or {@code null}
     * @throws TelegramApiException if the reply cannot be sent
     */
    private static void replyClosed(HandlerContext context, Message message, Language lang, String reportUuid, ClassificationFlag classification) throws TelegramApiException
    {
        removeInlineKeyboard(context, message);

        StringBuilder html = new StringBuilder(context.languages().get(lang, "operator_report", "closed_header", reportUuid));
        if (classification != null)
        {
            html.append(context.languages().get(lang, "operator_report", "closed_classification",
                    classification.name()));
        }
        replyHtml(context, "operator-report-closed", message, html.toString(), null);
    }
}
