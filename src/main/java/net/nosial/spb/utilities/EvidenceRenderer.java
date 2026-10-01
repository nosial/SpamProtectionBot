package net.nosial.spb.utilities;

import net.nosial.jfederation.enums.ClassificationFlag;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.FileAttachmentRecord;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Presents Federation evidence records in Telegram: the HTML summary of a record, and its file
 * attachments re-sent as documents.
 *
 * <p>Shared by the {@code /evidence} command and the evidence that follows a
 * {@code #REPORT_ASSIGNED} notification, so both show a record the same way.
 */
public final class EvidenceRenderer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceRenderer.class);

    private EvidenceRenderer()
    {
    }

    /**
     * Builds the HTML summary of an evidence record.
     *
     * @param lm the translations
     * @param lang the language to render in
     * @param evidence the evidence record
     * @return the formatted HTML text
     */
    public static String infoHtml(LanguageManager lm, Language lang, EvidenceRecord evidence)
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
        html.append(lm.get(lang, "evidence", "updated", MessageHelper.formatUpdated(lm, lang, evidence.updated()))).append('\n');

        appendMetadata(html, evidence.metadata(), lm, lang);

        return html.toString();
    }

    /**
     * Downloads the file attachments of an evidence record and sends each one as a document.
     * Failures are logged per attachment and never propagate.
     *
     * @param telegramClient the client to send with
     * @param federation the Federation service to download from
     * @param accessToken the operator's access token, or {@code null} to read as the bot
     * @param evidence the evidence record
     * @param chatId the chat to send the documents to
     * @param replyToMessageId the message the documents reply to, or {@code null}
     */
    public static void sendAttachments(TelegramClient telegramClient, FederationService federation, String accessToken,
                                       EvidenceRecord evidence, long chatId, Integer replyToMessageId)
    {
        List<FileAttachmentRecord> attachments;
        try
        {
            attachments = federation.evidenceAttachments(accessToken, evidence.uuid());
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
                    String downloadedPath = federation.downloadAttachment(accessToken, attachment.uuid(), tempDir.toAbsolutePath().toString());
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
                            .chatId(String.valueOf(chatId))
                            .document(new InputFile(file))
                            .caption(attachment.fileName() != null ? attachment.fileName() : attachment.uuid());
                    if (replyToMessageId != null)
                    {
                        documentBuilder.replyToMessageId(replyToMessageId);
                    }
                    telegramClient.execute(documentBuilder.build());
                }
                catch (FederationException e)
                {
                    LOGGER.debug("Failed to download attachment {}: {}", attachment.uuid(), e.getMessage());
                }
                catch (TelegramApiException e)
                {
                    LOGGER.debug("Failed to send attachment {} in chat {}: {}", attachment.uuid(), chatId, e.getMessage());
                }
            }
        }
        finally
        {
            deleteDirectory(tempDir);
        }
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
        html.append(HtmlEscape.escape(metadata.toPrettyString()));
        html.append("</pre>\n");
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
            LOGGER.debug("Failed to walk {}: {}", directory, e.getMessage());
        }
    }
}
