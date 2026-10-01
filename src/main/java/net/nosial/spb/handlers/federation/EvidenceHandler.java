package net.nosial.spb.handlers.federation;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import net.nosial.spb.objects.Language;
import net.nosial.spb.classes.FederationWebLinks;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.EvidenceRenderer;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageHelper;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;


/**
 * The {@code /evidence} command handler.
 *
 * <p>Queries the Federation server for a specific evidence record by UUID and displays its
 * information. When the caller is an authenticated operator, the request is made using
 * that operator's token.
 *
 * <p>In group chats the response is ephemeral so only the caller sees it. In private
 * chats a regular message is sent. File attachments associated with the evidence are
 * downloaded from Federation and sent as individual document messages replying to the
 * evidence info message.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "evidence")
public final class EvidenceHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceHandler.class);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        String[] args = MessageHelper.parseArguments(message.getText());
        if (args.length != 1 || !MessageHelper.isUuid(args[0]))
        {
            String error = context.languages().get(resolveLanguage(context, message), "evidence", "usage");
            sendHtml(context, message, error, isGroupChat(message), null);
            return;
        }

        handleEvidenceLookup(context, message, args[0]);
    }

    /**
     * Looks up an evidence record on the Federation server and displays its information.
     * If the caller is an authenticated operator, the request is made using that
     * operator's token. File attachments are downloaded and sent as individual documents
     * replying to the info message.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param evidenceUuid the Federation evidence UUID to look up
     * @throws TelegramApiException if a response cannot be sent
     */
    private void handleEvidenceLookup(HandlerContext context, Message message, String evidenceUuid) throws TelegramApiException
    {
        OperatorIdentity operatorIdentity = context.managers().operators()
                .getOperator(message.getFrom().getId()).orElse(null);

        // Confidential evidence is visible only to the operator it belongs to, so the lookup runs
        // as the caller when they are authenticated and as the bot otherwise.
        String accessToken = operatorIdentity != null ? operatorIdentity.accessToken() : null;

        try
        {
            EvidenceRecord evidence = context.federation().evidenceAs(accessToken, evidenceUuid).orElse(null);
            if (evidence == null)
            {
                sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "evidence", "not_found", HtmlEscape.escape(evidenceUuid)), false, null);
                return;
            }

            Language lang = resolveLanguage(context, message);
            String html = EvidenceRenderer.infoHtml(context.languages(), lang, evidence);
            InlineKeyboardMarkup markup = FederationWebLinks.attach(null,
                    context.webLinks().button(context.languages(), lang, FederationWebLinks.Record.EVIDENCE, evidence.uuid()));
            Message infoMessage;
            if (isGroupChat(message))
            {
                infoMessage = sendEphemeralHtmlAndReturn(context, message, html, markup);
            }
            else
            {
                infoMessage = replyHtml(context, "evidence-reply", message, html, markup);
            }

            // The info message is ephemeral in groups, so attachments cannot reply to it there.
            EvidenceRenderer.sendAttachments(context.telegramClient(), context.federation(), accessToken, evidence,
                    message.getChatId(), isGroupChat(message) ? null : infoMessage.getMessageId());
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch evidence {}: {}", evidenceUuid, e.getMessage());
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "evidence", "query_error"), false, null);
        }
    }

    /**
     * Sends an ephemeral HTML message in response to a given message and returns the sent message.
     *
     * @param context the per-update command context
     * @param message the incoming message to reply to
     * @param html the HTML content to send as the message text
     * @param markup the inline keyboard to attach, or {@code null} for none
     * @return the sent {@link Message} instance
     * @throws TelegramApiException if an error occurs while sending the message
     */
    private static Message sendEphemeralHtmlAndReturn(HandlerContext context, Message message, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        return execute(context, "evidence-ephemeral", SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .ephemeralMessageParameters(MessageHelper.ephemeralTo(message.getFrom() != null ? message.getFrom().getId() : null))
                .replyToMessageId(message.getMessageId())
                .messageThreadId(MessageHelper.topicId(message))
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());
    }
}
