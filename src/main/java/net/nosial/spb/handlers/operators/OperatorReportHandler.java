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
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles the action buttons on report-notification detail messages.
 *
 * <p>The buttons carry the report UUID themselves (see {@link OperatorReportCallback}), so they do
 * not expire and survive bot restarts. The report is closed with the credential currently stored
 * for the clicking Telegram user, in a short-lived Federation client, so the callback never
 * changes the shared client authentication used by update handlers. Federation decides whether
 * that operator may close the report.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = {OperatorReportCallback.PREFIX + ":", OperatorReportCallback.LEGACY_PREFIX + ":"})
public final class OperatorReportHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorReportHandler.class);
    private static final String CLAIM_KEY_PREFIX = "operator_report_close:";

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

        // Buttons sent before the actions became stateless point at an in-memory session that is gone.
        if (OperatorReportCallback.LEGACY_PREFIX.equals(parts[0]))
        {
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "expired_alert"));
            editExpired(context, callback, message, lang);
            return;
        }

        String reportUuid = parts[1];
        ClassificationFlag classification = classification(parts[2]);
        if (!MessageHelper.isUuid(reportUuid) || (!OperatorReportCallback.CLOSE.equals(parts[2]) && classification == null))
        {
            answer(context, callback);
            return;
        }

        // Notifications are only delivered to the operator's private chat with the bot.
        if (message.getChatId() == null || message.getChatId() != callback.getFrom().getId())
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
            editFailure(context, callback, message, lang, context.languages().get(lang, "operator_report", "federation_not_configured"));
            return;
        }

        // Guards against Telegram delivering the same press twice, or a double tap, sending two
        // close requests; the second would be rejected and overwrite the "closed" message.
        AtomicBoolean claim = (AtomicBoolean) context.cache().get(CLAIM_KEY_PREFIX + callback.getFrom().getId() + ":" + reportUuid,
                ignored -> new AtomicBoolean(false));
        if (!claim.compareAndSet(false, true))
        {
            answer(context, callback);
            return;
        }

        try
        {
            context.federation().closeReport(operator.accessToken(), reportUuid, classification);
            editClosed(context, callback, message, reportUuid, classification);
            answer(context, callback, context.languages().get(lang, "notifications", "report.closed"));
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to close report {} for Telegram operator {}: {}", reportUuid, callback.getFrom().getId(), e.getMessage());
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "federation_rejected_close"));
            editFailure(context, callback, message, lang, context.languages().get(lang, "operator_report", "federation_rejected_close"));
        }
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
     * Edits a message to indicate that an operator report has been closed.
     *
     * @param context the handler context providing necessary services and utilities
     * @param callback the callback query associated with the closed report
     * @param message the message to be edited to reflect the report closure
     * @param reportUuid the UUID of the closed report
     * @param classification an optional classification flag to include additional context
     *                       about the report closure; can be null
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void editClosed(HandlerContext context, CallbackQuery callback, Message message, String reportUuid, ClassificationFlag classification) throws TelegramApiException
    {
        Language lang = resolveLanguage(context, callback);
        StringBuilder html = new StringBuilder(context.languages().get(lang, "operator_report", "closed_header", reportUuid));
        if (classification != null)
        {
            html.append(context.languages().get(lang, "operator_report", "closed_classification",
                    classification.name()));
        }
        editMessage(context, message, callback, html.toString(), null);
    }

    /**
     * Edits a message to indicate that an operator report has expired.
     *
     * @param context the handler context providing necessary services and utilities
     * @param callback the callback query associated with the expired report
     * @param message the message to be edited to reflect the expiration
     * @param lang the language used for localization of the expiration message
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void editExpired(HandlerContext context, CallbackQuery callback, Message message, Language lang) throws TelegramApiException
    {
        editMessage(context, message, callback, context.languages().get(lang, "operator_report", "expired"), null);
    }

    /**
     * Edits a message to indicate a failure in the operator report process.
     *
     * @param context the handler context providing necessary services and utilities
     * @param callback the callback query associated with the failure event
     * @param message the message to be edited to reflect the failure
     * @param lang the language used for localization of the failure message
     * @param reason the specific reason for the failure, which will be included in the edited message
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void editFailure(HandlerContext context, CallbackQuery callback, Message message, Language lang, String reason) throws TelegramApiException
    {
        editMessage(context, message, callback, context.languages().get(lang, "operator_report", "failed_header", HtmlEscape.escape(reason)), null);
    }
}
