package net.nosial.spb.handlers.operators;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.ClassificationFlag;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.sessions.OperatorReportSessionManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.context.OperatorReportContext;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Consumes one-time actions on report-notification detail messages.
 *
 * <p>Only the Telegram operator bound to the memory-only session may close the report. The stored
 * operator credential is used in a short-lived Federation client, so the callback never changes
 * the shared client authentication used by update handlers.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = OperatorReportSessionManager.CALLBACK_PREFIX + ":")
public final class OperatorReportHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorReportHandler.class);

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

        ClassificationFlag classification = classification(parts[2]);
        if (!"close".equals(parts[2]) && classification == null)
        {
            answer(context, callback);
            return;
        }

        OperatorReportContext session = context.sessions().operatorReport().takeOwned(parts[1], callback.getFrom().getId());
        if (session == null)
        {
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "expired"));
            editExpired(context, callback, message, lang);
            return;
        }

        if (!context.federation().isAvailable())
        {
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "federation_not_configured"));
            editFailure(context, callback, message, lang, context.languages().get(lang, "operator_report", "federation_not_configured"));
            return;
        }

        try
        {
            context.federation().closeReport(session.operatorIdentity().accessToken(), session.reportUuid(), classification);
            editClosed(context, callback, message, session, classification);
            answer(context, callback, context.languages().get(lang, "notifications", "report.closed"));
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to close report {} for Telegram operator {}: {}", session.reportUuid(), callback.getFrom().getId(), e.getMessage());
            answerAlert(context, callback, context.languages().get(lang, "operator_report", "federation_rejected_close"));
            editFailure(context, callback, message, lang, context.languages().get(lang, "operator_report", "federation_rejected_close"));
        }
    }

    /**
     * Extracts a classification flag from the given action string. The action string
     * must start with the prefix "class:" to be considered valid. If the prefix is missing
     * or the classification value is invalid, the method returns null.
     *
     * @param action the action string containing the classification prefix and value
     * @return a {@code ClassificationFlag} if the action string is valid, or {@code null} otherwise
     */
    private static ClassificationFlag classification(String action)
    {
        if (!action.startsWith("class:"))
        {
            return null;
        }
        try
        {
            return ClassificationFlag.valueOf(action.substring("class:".length()));
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
     * @param session the operator report session containing details of the closed report
     * @param classification an optional classification flag to include additional context
     *                       about the report closure; can be null
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void editClosed(HandlerContext context, CallbackQuery callback, Message message, OperatorReportContext session, ClassificationFlag classification) throws TelegramApiException
    {
        Language lang = resolveLanguage(context, callback);
        StringBuilder html = new StringBuilder(context.languages().get(lang, "operator_report", "closed_header", session.reportUuid()));
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
