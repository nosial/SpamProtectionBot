package net.nosial.spb.handlers.federation;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import com.fasterxml.jackson.databind.JsonNode;
import net.nosial.jfederation.enums.ClassificationFlag;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.FileAttachmentRecord;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageHelper;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

            String html = buildEvidenceInfoHtml(context.languages(), resolveLanguage(context, message), evidence);
            Message infoMessage;
            if (isGroupChat(message))
            {
                infoMessage = sendEphemeralHtmlAndReturn(context, message, html);
            }
            else
            {
                infoMessage = replyHtml(context, "evidence-reply", message, html, null);
            }

            sendAttachments(context, message, infoMessage, evidence, accessToken);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch evidence {}: {}", evidenceUuid, e.getMessage());
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "evidence", "query_error"), false, null);
        }
    }

    /**
     * Downloads and sends file attachments associated with an evidence record. Each
     * attachment is sent as a document replying to the evidence info message.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param infoMessage the evidence info message to reply to
     * @param evidence the evidence record
     * @param accessToken the operator's access token, or {@code null} to read as the bot
     */
    private static void sendAttachments(HandlerContext context, Message message, Message infoMessage, EvidenceRecord evidence, String accessToken)
    {
        List<FileAttachmentRecord> attachments;
        try
        {
            attachments = context.federation().evidenceAttachments(accessToken, evidence.uuid());
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch attachments for evidence {}: {}", evidence.uuid(), e.getMessage());
            return;
        }

        if (attachments.isEmpty())
        {
            return;
        }

        Path tempDir;
        try
        {
            tempDir = Files.createTempDirectory("spb-evidence-");
        }
        catch (IOException e)
        {
            LOGGER.warn("Failed to create temp directory for evidence attachments: {}", e.getMessage());
            return;
        }

        try
        {
            for (FileAttachmentRecord attachment : attachments)
            {
                try
                {
                    String downloadedPath = context.federation().downloadAttachment(accessToken, attachment.uuid(), tempDir.toAbsolutePath().toString());
                    if (downloadedPath == null)
                    {
                        LOGGER.warn("Download returned null for attachment {}", attachment.uuid());
                        continue;
                    }

                    File file = new File(downloadedPath);
                    if (!file.exists())
                    {
                        LOGGER.warn("Downloaded file does not exist: {}", downloadedPath);
                        continue;
                    }

                    SendDocument.SendDocumentBuilder<?, ?> documentBuilder = SendDocument.builder()
                            .chatId(String.valueOf(message.getChatId()))
                            .document(new InputFile(file))
                            .caption(attachment.fileName() != null ? attachment.fileName() : attachment.uuid());
                    if (!isGroupChat(message))
                    {
                        documentBuilder.replyToMessageId(infoMessage.getMessageId());
                    }
                    context.telegramClient().execute(documentBuilder.build());
                }
                catch (FederationException e)
                {
                    LOGGER.debug("Failed to download attachment {}: {}", attachment.uuid(), e.getMessage());
                }
                catch (TelegramApiException e)
                {
                    LOGGER.debug("Failed to send attachment {} in chat {}: {}",
                            attachment.uuid(), message.getChatId(), e.getMessage());
                }
            }
        }
        finally
        {
            deleteDirectory(tempDir);
        }
    }

    /**
     * Builds the HTML summary of an evidence record.
     *
     * @param evidence the evidence record
     * @return the formatted HTML text
     */
    private static String buildEvidenceInfoHtml(LanguageManager lm, Language lang, EvidenceRecord evidence)
    {
        StringBuilder html = new StringBuilder(lm.get(lang, "evidence", "header"));
        html.append(lm.get(lang, "evidence", "evidence_id", HtmlEscape.escape(evidence.uuid()))).append('\n');

        if (evidence.classificationFlag() != null)
        {
            html.append(lm.get(lang, "evidence", "classification", classificationDisplay(lm, lang, evidence.classificationFlag()))).append('\n');
        }

        if (evidence.tag() != null && !evidence.tag().isBlank())
        {
            html.append(lm.get(lang, "evidence", "tag", HtmlEscape.escape(evidence.tag()))).append('\n');
        }

        html.append(lm.get(lang, "evidence", "confidential", lm.get(lang, "general", evidence.confidential() ? "yes" : "no"))).append('\n');

        if (evidence.textContent() != null && !evidence.textContent().isBlank())
        {
            html.append(lm.get(lang, "evidence", "content", HtmlEscape.escape(evidence.textContent()))).append('\n');
        }

        if (evidence.note() != null && !evidence.note().isBlank())
        {
            html.append(lm.get(lang, "evidence", "note", HtmlEscape.escape(evidence.note()))).append('\n');
        }

        html.append('\n');
        html.append(lm.get(lang, "evidence", "entity", HtmlEscape.escape(evidence.entityUuid()))).append('\n');
        html.append(lm.get(lang, "evidence", "submitted_by", HtmlEscape.escape(evidence.operatorUuid()))).append('\n');

        if (evidence.report() != null && !evidence.report().isBlank())
        {
            html.append(lm.get(lang, "evidence", "report", HtmlEscape.escape(evidence.report()))).append('\n');
        }

        html.append(lm.get(lang, "evidence", "created", MessageHelper.formatTimestamp(evidence.created()))).append('\n');
        html.append(lm.get(lang, "evidence", "updated", MessageHelper.formatTimestamp(evidence.updated()))).append('\n');

        appendMetadata(html, evidence.metadata(), lm, lang);

        return html.toString();
    }

    /**
     * Appends metadata to an HTML StringBuilder formatted as a prettified JSON string.
     * If the metadata is null, missing, or empty, the method does nothing.
     *
     * @param html the StringBuilder instance to which the metadata will be appended
     * @param metadata the metadata in JSON format to append to the HTML
     * @param lm the LanguageManager instance to retrieve localized strings
     * @param lang the target language for metadata-related labels
     */
    private static void appendMetadata(StringBuilder html, JsonNode metadata, LanguageManager lm, Language lang)
    {
        if (metadata == null || metadata.isNull() || metadata.isMissingNode())
        {
            return;
        }
        html.append('\n');
        html.append(lm.get(lang, "evidence", "metadata_header"));
        try
        {
            html.append(HtmlEscape.escape(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(metadata)));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e)
        {
            html.append(HtmlEscape.escape(metadata.toString()));
        }
        html.append("</pre>\n");
    }

    /**
     * Sends an ephemeral HTML message in response to a given message and returns the sent message.
     *
     * @param context the per-update command context
     * @param message the incoming message to reply to
     * @param html the HTML content to send as the message text
     * @return the sent {@link Message} instance
     * @throws TelegramApiException if an error occurs while sending the message
     */
    private static Message sendEphemeralHtmlAndReturn(HandlerContext context, Message message, String html) throws TelegramApiException
    {
        return execute(context, "evidence-ephemeral", SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .receiverUserId(message.getFrom() != null ? message.getFrom().getId() : null)
                .replyToMessageId(message.getMessageId())
                .messageThreadId(MessageHelper.topicId(message))
                .text(html)
                .parseMode(ParseMode.HTML)
                .build());
    }

    /**
     * Returns the display label for a classification flag.
     *
     * @param lm the language manager
     * @param lang the target language
     * @param flag the classification flag
     * @return the display label
     */
    private static String classificationDisplay(LanguageManager lm, Language lang, ClassificationFlag flag)
    {
        return switch (flag)
        {
            case MALICIOUS -> lm.get(lang, "evidence", "classification_malicious");
            case SUSPICIOUS -> lm.get(lang, "evidence", "classification_suspicious");
            case NORMAL -> lm.get(lang, "evidence", "classification_normal");
        };
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory the path to the directory to be deleted
     */
    private static void deleteDirectory(Path directory)
    {
        try (Stream<Path> files = Files.walk(directory).sorted(Comparator.reverseOrder()))
        {
            files.forEach(path ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    LOGGER.debug("Failed to delete {}: {}", path, e.getMessage());
                }
            });
        }
        catch (IOException e)
        {
            LOGGER.debug("Failed to walk temp directory {}: {}", directory, e.getMessage());
        }
    }
}
