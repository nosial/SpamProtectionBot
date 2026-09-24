package net.nosial.spb.classes;

import net.nosial.spb.enums.ReportOrigin;
import net.nosial.jfederation.records.ContentInput;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.ReportSubmission;
import net.nosial.spb.classes.managers.UserManager;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.ChatInfo;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.spb.objects.ReportActionState;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.ReportContext;
import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.nosial.spb.classes.notifications.NotificationSender;
import net.nosial.spb.utilities.MessageHelper;

/**
 * Creates reports on the Federation server, whoever asked for one.
 *
 * <p>Three different features end in the same place: a member reporting a message with
 * {@code /report}, a moderator marking a scan result as wrong, and an operator blacklisting an
 * entity against a freshly created supporting report. They differ only in what they do afterwards
 * — whether a summary goes back to the chat, whether moderators are notified, which evidence tag
 * is attached, and whose credentials the submission is made under. Everything before that point is
 * identical, so it lives here rather than being reached into across three handler packages.
 *
 * <p>Submission never throws on failure. Federation being unreachable must not lose the user's
 * report silently either, so the caller is told through the returned UUID being {@code null} and
 * the user is told through a message in their own language.
 */
public final class ReportSubmissionService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportSubmissionService.class);

    /**
     * Federation addresses every Telegram entity under this host.
     */
    static final String TELEGRAM_ENTITY_SUFFIX = "@telegram.org";

    /**
     * Callback data of the Dismiss button on a submission summary.
     *
     * <p>It belongs to the report handler's namespace because that is what answers the press; it
     * is declared here because this is what puts the button on the message.
     */
    public static final String DISMISS_CALLBACK = "report:dismiss";

    /**
     * Submits the report described by the session and sends a basic HTML summary by editing the
     * existing report prompt when available, or by sending a new response in the same originating
     * chat when no prompt exists.
     *
     * <p>An administrator's own {@code /report} still reaches Federation and still summarises back
     * to them, but does not also notify the chat's moderators — the reporter already is one, so
     * there is nobody left to alert.
     *
     * @param context the per-update command context
     * @param session the report session with target message metadata
     * @param reportMessage optional moderator comment, may be {@code null}
     * @throws TelegramApiException if the report submission API call fails unexpectedly
     */
    public static void submit(HandlerContext context, ReportContext session, String reportMessage) throws TelegramApiException
    {
        ReportOrigin origin = session.reporterIsAdmin() ? ReportOrigin.ADMIN : ReportOrigin.MEMBER;
        submitInternal(context, session, origin, reportMessage, null);
    }

    /**
     * Submits the evidence captured by a scanning notification as a one-time false-positive
     * report. Unlike a user-initiated {@code /report}, confirmation is returned by the callback
     * query and the notification message is updated, so no summary is posted to the protected chat.
     *
     * @return the submitted report UUID, or {@code null} when Federation rejected the report
     */
    public static String submitFalsePositive(HandlerContext context, ReportContext session) throws TelegramApiException
    {
        return submitInternal(context, session, ReportOrigin.FALSE_POSITIVE, null, null);
    }

    /**
     * Submits a report on behalf of an authenticated operator purely to obtain a supporting report
     * reference, typically to back a {@code /blacklist} record.
     *
     * <p>The report is created exactly like a user-initiated report (evidence, metadata, and
     * attachments are captured and uploaded) but neither a moderator notification nor a user
     * summary is emitted: the report exists solely as a reference for the blacklist.
     *
     * @return the submitted report UUID, or {@code null} when Federation rejected the report
     */
    public static String submitOperatorReference(HandlerContext context, String accessToken, ReportContext session) throws TelegramApiException
    {
        return submitInternal(context, session, ReportOrigin.OPERATOR_REFERENCE, null, accessToken);
    }

    /**
     * Creates the report for the given session and returns its UUID.
     *
     * @param context the per-update context
     * @param session the report dialog the submission comes from
     * @param origin who asked for the report, which decides its tag and who hears about it
     * @param reportMessage the reporter's comment, or {@code null}
     * @param accessToken the operator's access token, or {@code null} to submit as the bot
     * @return the submitted report UUID, or {@code null} when Federation rejected the report
     * @throws TelegramApiException if the summary or error reply cannot be sent
     */
    private static String submitInternal(HandlerContext context, ReportContext session,
                                         ReportOrigin origin, String reportMessage, String accessToken) throws TelegramApiException
    {
        Language lang = context.languages().defaultLanguage();
        if (!context.federation().isAvailable())
        {
            if (origin.isSummarised())
            {
                sendError(context, lang, session, context.languages().get(lang, "report_submit", "not_configured"));
            }
            return null;
        }

        // Submitting as the bot itself is refused unconditionally without client permissions, no
        // matter what the server allows anonymously, so there is nothing to gain from attempting
        // it. An operator-backed submission (a non-null accessToken) is unaffected: it lives or
        // dies on that operator's own permissions, not the bot's.
        if (accessToken == null && !context.federation().isAuthenticated())
        {
            if (origin.isSummarised())
            {
                sendError(context, lang, session, context.languages().get(lang, "report_submit", "not_authorized"));
            }
            return null;
        }

        Message updateMessage = context.update().getMessage();
        if (updateMessage != null)
        {
            String chatType = updateMessage.getChat().getType();
            if ("group".equals(chatType) || "supergroup".equals(chatType))
            {
                lang = context.managers().languagePreferences()
                        .getChatLanguage(updateMessage.getChatId());
            }
            else if (updateMessage.getFrom() != null)
            {
                lang = context.managers().languagePreferences()
                        .getUserLanguage(updateMessage.getFrom().getId());
            }
        }
        String reportingEntity = session.targetAuthorId() + TELEGRAM_ENTITY_SUFFIX;
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(session.chatId());
        ContentInput evidence = buildEvidence(session, configuration.privacyMode(), origin.evidenceTag());

        LOGGER.debug("Submitting report for entity {} from reporter {} in chat {}: incidentType={}, targetMessageId={}, textLength={}",
                reportingEntity, session.reporterId(), session.chatId(), session.incidentType(),
                session.targetMessageId(), evidence.textContent() != null ? evidence.textContent().length() : 0);

        try
        {
            if (configuration.privacyMode())
            {
                ensureReportingEntity(context.federation(), session.targetAuthorId());
            }

            ReportSubmission submission = context.federation().submitReport(accessToken, reportingEntity, evidence, session.incidentType(), reportMessage);
            uploadAttachments(context, accessToken, submission, session, configuration.privacyMode());
            LOGGER.debug("Report submitted successfully: uuid={}", submission.getReport().uuid());
            if (origin.notifiesModerators() && configuration.reportingNotificationsEnabled()
                    && !ReportOrigin.FALSE_REPORT_EVIDENCE_TAG.equals(origin.evidenceTag()))
            {
                String reportUuid = submission.getReport().uuid();
                String html = notificationHtml(context.languages(), lang, context, session, reportUuid, reportMessage);
                List<NotificationTarget> targets = NotificationSender.notifyReportSubmitted(context, session.chatId(),
                                html, reportUuid, session.targetMessageId(), session.targetAuthorId(), true);
                context.cache().put("report_notifications:" + reportUuid, new ReportActionState(targets));
            }
            if (origin.isSummarised())
            {
                sendSummary(context, lang, session, submission, reportMessage, origin);
            }
            return submission.getReport().uuid();
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to submit report for message {} in chat {}: {}", session.targetMessageId(), session.chatId(), e.getMessage());
            if (origin.isSummarised())
            {
                sendError(context, lang, session, context.languages().get(lang, "report_submit", "submit_failed"));
            }
            return null;
        }
    }

    public static String notificationHtml(LanguageManager lm, Language lang, HandlerContext context,
                                   ReportContext session, String reportUuid, String reportMessage)
    {
        ChatInfo chat = context.chatInfo().getIfPresent(session.chatId());
        String chatName = chat == null ? null : chat.name();
        UserManager users = context.database() != null ? context.managers().users() : null;

        return lm.get(lang, "report_submit", "title") + lm.get(lang, "report_submit", "chat",
                NotificationFormatter.chatReference(chatName, session.chatId(), lang)) + '\n' +
                lm.get(lang, "report_submit", "reported_user", NotificationFormatter.userMention(session.targetAuthorId(), users, lang)) + '\n' +
                lm.get(lang, "report_submit", "reporter", NotificationFormatter.userMention(session.reporterId(), users, lang)) + '\n' +
                lm.get(lang, "report_submit", "report_type", HtmlEscape.escape(MessageHelper.displayName(session.incidentType()))) + '\n' +
                lm.get(lang, "report_submit", "report_id", HtmlEscape.escape(reportUuid)) + '\n' +
                lm.get(lang, "report_submit", reportMessage != null && !reportMessage.isBlank()
                        ? "comment" : "comment_none", reportMessage != null && !reportMessage.isBlank()
                        ? NotificationFormatter.escapeAndTruncate(reportMessage, 800, lang) : reportMessage) +
                '\n';
    }

    /**
     * Creates the report target with no metadata when privacy mode has intentionally skipped
     * routine entity synchronization. A report cannot be submitted for an entity unknown to the
     * Federation server, while this push discloses only the target identifier the reporter chose.
     */
    public static void ensureReportingEntity(FederationService federation, long targetAuthorId) throws FederationException
    {
        if (!federation.isAuthenticated())
        {
            return;
        }

        federation.publishEntity("telegram.org", String.valueOf(targetAuthorId), null);
    }

    /**
     * Builds the evidence content input for a report based on the provided session data and options.
     *
     * @param session the report context containing information about the target message, author, and metadata
     * @param privacyMode a flag indicating whether privacy mode is enabled, where certain metadata is omitted
     * @param evidenceTag an optional tag to specify the type of evidence; if null, a default tag is determined
     * @return a {@code ContentInput} instance representing the captured evidence, including text content,
     *         metadata, and the associated evidence tag
     */
    static ContentInput buildEvidence(ReportContext session, boolean privacyMode, String evidenceTag)
    {
        String textContent = session.targetText() != null && !session.targetText().isBlank()
                ? session.targetText() : "No text content";
        if (privacyMode && evidenceTag == null)
        {
            return new ContentInput(textContent);
        }

        Map<String, Object> metadata = null;
        if (!privacyMode)
        {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("chat_id", session.chatId());
            fields.put("message_id", session.targetMessageId());
            fields.put("author_id", session.targetAuthorId());
            fields.put("reporter_id", session.reporterId());
            fields.put("reported_at", System.currentTimeMillis() / 1000);
            // The reported message itself, every property of it, after the report's own fields.
            metadata = FlatMetadata.withFields(fields, session.targetMetadata());
        }

        String tag = evidenceTag != null ? evidenceTag
                : session.reporterIsAdmin() ? ReportOrigin.OPERATOR_REFERENCE.evidenceTag() : ReportOrigin.MEMBER.evidenceTag();
        LOGGER.debug("Built evidence for report: authorId={}, targetMessageId={}, textContent={}",
                session.targetAuthorId(), session.targetMessageId(), textContent);
        return new ContentInput(textContent, null, tag, false, metadata);
    }

    /**
     * Uploads the attachments associated with a report submission to the Federation server.
     * If privacy mode is enabled or no attachments are available in the current session,
     * the method exits without performing the upload.
     *
     * @param context the per-update command context
     * @param accessToken the authentication token for accessing the Federation server
     * @param submission the report submission containing evidence records
     * @param session the session holding the attachments to be uploaded
     * @param privacyMode a flag indicating whether privacy-sensitive behavior is enabled
     */
    private static void uploadAttachments(HandlerContext context, String accessToken, ReportSubmission submission, ReportContext session, boolean privacyMode)
    {
        if (privacyMode || session.attachments() == null || session.attachments().isEmpty())
        {
            return;
        }

        List<EvidenceRecord> evidence = submission.getEvidence();
        if (evidence.isEmpty() || evidence.get(0).uuid() == null)
        {
            LOGGER.warn("Report {} returned no evidence record; attachments were not uploaded", submission.getReport().uuid());
            return;
        }

        String evidenceUuid = evidence.get(0).uuid();
        for (ReportAttachment attachment : session.attachments())
        {
            uploadAttachment(context, accessToken, evidenceUuid, attachment);
        }
    }

    /**
     * Handles the process of uploading an attachment to a specified evidence record.
     * <p>
     * This method stages the attachment data as a file, either from cached content or via
     * downloading it, and uploads it to a designated storage using the provided context.
     * Temporary files, if created, will be cleaned up automatically after the operation.
     *
     * @param context The handler context that provides access to the telegram client
     *                      and federation service for uploading the attachment.
     * @param accessToken A valid access token used for authenticating the upload request.
     * @param evidenceUuid The UUID of the evidence record to which the attachment is being uploaded.
     * @param attachment The attachment object containing the necessary metadata, like file ID
     *                   and cached content, to handle the staging and uploading process.
     */
    private static void uploadAttachment(HandlerContext context, String accessToken, String evidenceUuid, ReportAttachment attachment)
    {
        File staged = null;
        try
        {
            String fileName;
            byte[] cachedContent = attachment.content();
            if (cachedContent != null && cachedContent.length > 0)
            {
                fileName = attachment.fileName() != null && !attachment.fileName().isBlank() ? attachment.fileName() : "telegram-" + attachment.fileId();
                staged = stageBytesToFile(cachedContent, fileName);
            }
            else
            {
                org.telegram.telegrambots.meta.api.objects.File telegramFile = context.telegramClient().execute(GetFile.builder().fileId(attachment.fileId()).build());
                File downloaded = context.telegramClient().downloadFile(telegramFile);
                fileName = uploadFileName(attachment, telegramFile);
                staged = stageUploadFile(downloaded, fileName);
            }
            context.federation().uploadAttachment(accessToken, evidenceUuid, staged.getAbsolutePath(), fileName);
            LOGGER.debug("Uploaded Telegram file {} to evidence {}", attachment.fileId(), evidenceUuid);
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to upload Telegram file {} for evidence {}: {}", attachment.fileId(), evidenceUuid, e.getMessage());
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
                    LOGGER.debug("Could not delete temporary upload {}: {}", staged, e.getMessage());
                }
            }
        }
    }

    /**
     * Determines the file name to be used for uploading a file. If the attachment has a non-blank
     * file name, it returns that name. Otherwise, it attempts to extract the file name from the
     * provided Telegram file's file path. If no valid file name is found, a default name is generated
     * using the attachment's file ID.
     *
     * @param attachment the {@code ReportAttachment} object containing file metadata, including an optional file name.
     * @param telegramFile the {@code File} object from the Telegram API, containing details about the file, such as its path.
     * @return the determined file name as a {@code String}, either from the attachment, the file's path, or a default name.
     */
    static String uploadFileName(ReportAttachment attachment, org.telegram.telegrambots.meta.api.objects.File telegramFile)
    {
        if (attachment.fileName() != null && !attachment.fileName().isBlank())
        {
            return attachment.fileName();
        }
        String filePath = telegramFile.getFilePath();
        if (filePath != null && !filePath.isBlank())
        {
            Path fileName = Path.of(filePath).getFileName();
            if (fileName != null && !fileName.toString().isBlank())
            {
                return fileName.toString();
            }
        }
        return "telegram-" + attachment.fileId();
    }

    /**
     * Sends a summary report containing details about a report submission.
     * This method constructs a localized message using the provided context,
     * language, session information, and other relevant parameters, and sends
     * the message to the appropriate recipient.
     *
     * @param context The handler context used for accessing resources such as language and database managers.
     * @param lang The language in which the summary should be localized.
     * @param session The report session context containing details about the report and its target.
     * @param submission The report submission object containing the report data.
     * @param reportMessage Additional message or comment to include in the report summary, if available.
     * @param origin The origin of the report, including evidence details.
     * @throws TelegramApiException If there is an error while sending the summary via the Telegram API.
     */
    private static void sendSummary(HandlerContext context, Language lang, ReportContext session,
                                    ReportSubmission submission, String reportMessage, ReportOrigin origin) throws TelegramApiException
    {
        LanguageManager lm = context.languages();
        String reportUuid = submission.getReport().uuid();
        String incidentType = MessageHelper.displayName(session.incidentType());
        String evidenceTag = origin.evidenceTag();
        UserManager users = context.database() != null ? context.managers().users() : null;

        StringBuilder html = new StringBuilder(lm.get(lang, "report_submit", "summary_header"));
        html.append(lm.get(lang, "report_submit", "summary_report_id", HtmlEscape.escape(reportUuid))).append('\n');
        html.append(lm.get(lang, "report_submit", "summary_incident_type", HtmlEscape.escape(incidentType))).append('\n');
        html.append(lm.get(lang, "report_submit", "summary_reported_user", NotificationFormatter.userMention(session.targetAuthorId(), users, lang))).append('\n');
        html.append(lm.get(lang, "report_submit", "summary_evidence_tag", HtmlEscape.escape(evidenceTag))).append('\n');
        if (reportMessage != null && !reportMessage.isBlank())
        {
            html.append(lm.get(lang, "report_submit", "summary_comment", HtmlEscape.escape(reportMessage))).append('\n');
        }
        html.append(lm.get(lang, "report_submit", "summary_dismiss"));
        sendOrEdit(context, session, html.toString(), dismissMarkup(lm, lang));
    }

    /**
     * Sends an error message with localized text to the specified ReportContext session.
     *
     * @param context the handler context containing necessary dependencies for sending the error
     * @param lang the language in which the error message will be localized
     * @param session the report context representing the current user session
     * @param text the error message text to be displayed to the user
     * @throws TelegramApiException if there is an issue sending the error message
     */
    private static void sendError(HandlerContext context, Language lang, ReportContext session, String text) throws TelegramApiException
    {
        LanguageManager lm = context.languages();
        String html = lm.get(lang, "report_submit", "error_header") + HtmlEscape.escape(text) + "\n\n" + lm.get(lang, "report_submit", "error_dismiss");
        sendOrEdit(context, session, html, dismissMarkup(lm, lang));
    }

    /**
     * Sends a message or edits an existing one depending on the context.
     *
     * @param context the handler context containing necessary information for the operation
     * @param session the report context with details about the current session
     * @param html the HTML content to be sent or updated in the message
     * @param markup the inline keyboard markup to accompany the message
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void sendOrEdit(HandlerContext context, ReportContext session, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        sendInOriginChat(context, session, html, markup);
    }

    /**
     * Creates an InlineKeyboardMarkup object with a single button for dismissing an action.
     *
     * @param lm the LanguageManager instance used to retrieve localized text
     * @param lang the Language object representing the current language for localization
     * @return an InlineKeyboardMarkup object containing a dismiss button
     */
    public static InlineKeyboardMarkup dismissMarkup(LanguageManager lm, Language lang)
    {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(lm.get(lang, "general", "dismiss"))
                        .callbackData(DISMISS_CALLBACK)
                        .build()))
                .build();
    }

    /**
     * Stages a downloaded file for upload by renaming and moving it to a temporary location.
     *
     * @param downloaded The downloaded file to be staged.
     * @param fileName The proposed file name for the upload, used to determine the file extension.
     * @return A File object referencing the staged file in the temporary location.
     * @throws Exception If an error occurs during file operations, such as creating or moving the file.
     */
    private static File stageUploadFile(File downloaded, String fileName) throws Exception
    {
        Path filePath = Path.of(fileName).getFileName();
        String name = filePath != null ? filePath.toString() : "attachment";
        int extensionStart = name.lastIndexOf('.');
        String extension = extensionStart >= 0 ? name.substring(extensionStart) : "";
        if (extension.length() > 20)
        {
            extension = "";
        }
        Path staged = Files.createTempFile("spb-report-", extension);
        Files.move(downloaded.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
        return staged.toFile();
    }

    /**
     * Stages eagerly captured raw bytes into a temporary file ready for Federation upload, so the
     * attachment survives the source message being deleted from Telegram.
     *
     * @param content the raw file bytes
     * @param fileName the display name used to derive the temporary extension
     * @return the staged temporary file
     * @throws Exception if the temporary file cannot be written
     */
    public static File stageBytesToFile(byte[] content, String fileName) throws Exception
    {
        Path filePath = Path.of(fileName).getFileName();
        String name = filePath != null ? filePath.toString() : "attachment";
        int extensionStart = name.lastIndexOf('.');
        String extension = extensionStart >= 0 ? name.substring(extensionStart) : "";
        if (extension.length() > 20)
        {
            extension = "";
        }
        Path staged = Files.createTempFile("spb-report-", extension);
        Files.write(staged, content);
        return staged.toFile();
    }

    /**
     * Sends a message or updates an existing message in the origin chat depending on the session context.
     *
     * @param context The handler context containing the Telegram client and other necessary dependencies.
     * @param session The report session context containing chat and message-related information.
     * @param html The message content in HTML format to be sent or updated.
     * @param markup The inline keyboard markup to be attached to the message.
     * @throws TelegramApiException If an error occurs while interacting with the Telegram API.
     */
    private static void sendInOriginChat(HandlerContext context, ReportContext session, String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        if (session.promptMessageId() == null)
        {
            context.telegramClient().execute(SendMessage.builder()
                    .chatId(String.valueOf(session.chatId()))
                    .messageThreadId(session.messageThreadId())
                    .receiverUserId(session.ephemeral() ? session.reporterId() : null)
                    .text(html)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(markup)
                    .build());
            return;
        }

        if (session.ephemeral())
        {
            context.telegramClient().execute(EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(session.chatId()))
                    .receiverUserId(session.reporterId())
                    .ephemeralMessageId(session.promptMessageId())
                    .text(html)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(markup)
                    .build());
            return;
        }

        context.telegramClient().execute(EditMessageText.builder()
                .chatId(String.valueOf(session.chatId()))
                .messageId(session.promptMessageId())
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());
    }
}
