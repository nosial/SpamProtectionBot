package net.nosial.spb.classes.notifications;

import net.nosial.spb.classes.FederationWebLinks;
import net.nosial.spb.classes.interfaces.NotificationSink;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.managers.ManagerRegistry;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.EntityResolver;
import net.nosial.spb.utilities.EvidenceRenderer;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Objects;

/**
 * Delivers a report notification to an operator when a report is assigned to them.
 *
 * <p>The message begins with {@code #REPORT_ASSIGNED} and contains the report metadata, the
 * report message (if any), a list of evidence UUIDs, and the operator action keyboard. Each evidence
 * record then follows as a reply to it, with the record's file attachments replying to the record.
 */
final class ReportNotificationSender implements NotificationSink
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportNotificationSender.class);
    private static final int EVIDENCE_PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_TEXT_LENGTH = 3_500;

    private final OkHttpTelegramClient telegramClient;
    private final FederationService federation;
    private final ManagerRegistry managers;
    private final FederationWebLinks webLinks;

    /**
     * Constructs a ReportNotificationSender object for handling the sending of report notifications.
     *
     * @param telegramClient the OkHttpTelegramClient instance used for sending messages via Telegram; must not be null.
     * @param federation the FederationService instance used for interacting with federated services; must not be null.
     * @param managers the ManagerRegistry instance used for accessing various manager components; must not be null.
     * @param webLinks the links into the Federation Web Application attached to each message; must not be null.
     */
    ReportNotificationSender(OkHttpTelegramClient telegramClient, FederationService federation, ManagerRegistry managers, FederationWebLinks webLinks)
    {
        this.webLinks = Objects.requireNonNull(webLinks, "webLinks must not be null");
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
        this.federation = Objects.requireNonNull(federation, "federation must not be null");
        this.managers = Objects.requireNonNull(managers, "managers must not be null");
    }

    @Override
    public void send(long telegramUserId, ReportRecord report) throws Exception
    {
        Language lang = this.managers.languagePreferences().getUserLanguage(telegramUserId);
        List<EvidenceRecord> evidence = loadEvidence(this.federation, report.uuid());
        List<String> evidenceIds = evidence.stream().map(EvidenceRecord::uuid).toList();

        Message notification = this.telegramClient.execute(SendMessage.builder()
                .chatId(String.valueOf(telegramUserId))
                .text(detailsHtml(report, evidenceIds, lang))
                .parseMode(ParseMode.HTML)
                .replyMarkup(FederationWebLinks.attach(actionMarkup(NotificationFormatter.languageManager(), lang, report.uuid()),
                        this.webLinks.button(NotificationFormatter.languageManager(), lang, FederationWebLinks.Record.REPORT, report.uuid())))
                .build());

        sendEvidence(telegramUserId, notification, evidence, lang);
    }

    /**
     * Sends each evidence record of the report as a reply to its notification, followed by the
     * record's file attachments as replies to the record, so the operator sees the report and its
     * evidence together.
     *
     * <p>The notification has already been delivered at this point, so a failure is logged and
     * never propagates: the caller would otherwise treat the whole notification as undelivered.
     *
     * @param telegramUserId the operator's Telegram user id
     * @param notification the delivered {@code #REPORT_ASSIGNED} message
     * @param evidence the report's evidence records
     * @param lang the operator's language
     */
    private void sendEvidence(long telegramUserId, Message notification, List<EvidenceRecord> evidence, Language lang)
    {
        if (evidence.isEmpty())
        {
            return;
        }

        // Confidential evidence and its files are visible only to the operator they belong to, so
        // attachments are read with the operator's own credential when one is stored.
        String accessToken = this.managers.operators().getOperator(telegramUserId)
                .map(OperatorIdentity::accessToken).orElse(null);
        for (EvidenceRecord record : evidence)
        {
            Integer replyToId = notification.getMessageId();
            try
            {
                Message info = this.telegramClient.execute(SendMessage.builder()
                        .chatId(String.valueOf(telegramUserId))
                        .text(EvidenceRenderer.infoHtml(NotificationFormatter.languageManager(), lang, record))
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(FederationWebLinks.attach(null, this.webLinks.button(
                                NotificationFormatter.languageManager(), lang, FederationWebLinks.Record.EVIDENCE, record.uuid())))
                        .replyToMessageId(notification.getMessageId())
                        .build());
                replyToId = info.getMessageId();
            }
            catch (TelegramApiException e)
            {
                LOGGER.warn("Unable to send evidence {} to Telegram user {}: {}", record.uuid(), telegramUserId, e.getMessage());
            }
            EvidenceRenderer.sendAttachments(this.telegramClient, this.federation, accessToken, record, telegramUserId, replyToId);
        }
    }

    /**
     * Generates an inline keyboard markup for action selection on the given report.
     *
     * @param lm the LanguageManager instance used for retrieving localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     * @param reportUuid the UUID of the report the buttons act on; must not be null.
     * @return an instance of InlineKeyboardMarkup containing the configured keyboard layout for actions.
     */
    static InlineKeyboardMarkup actionMarkup(LanguageManager lm, Language lang, String reportUuid)
    {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close"))
                                .callbackData(OperatorReportCallback.data(reportUuid, OperatorReportCallback.CLOSE)).build(),
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_normal"))
                                .callbackData(OperatorReportCallback.data(reportUuid, "NORMAL")).build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_suspicious"))
                                .callbackData(OperatorReportCallback.data(reportUuid, "SUSPICIOUS")).build(),
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_malicious"))
                                .callbackData(OperatorReportCallback.data(reportUuid, "MALICIOUS")).build()))
                .build();
    }

    /**
     * Constructs an HTML-formatted string containing detailed information about a report.
     *
     * @param report the ReportRecord instance containing the details of the report; must not be null.
     * @param evidenceIds a list of evidence IDs associated with the report; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     * @return a string containing the report details formatted in localized HTML.
     */
    private String detailsHtml(ReportRecord report, List<String> evidenceIds, Language lang)
    {
        return builderHtml(report, evidenceIds, NotificationFormatter.languageManager(), lang,
                EntityResolver.resolve(this.federation, this.managers.users(), report.reportingEntity()),
                EntityResolver.resolve(this.federation, this.managers.users(), report.submittingOperator()));
    }

    /**
     * Builds an HTML-formatted string for a report notification based on the provided report details,
     * evidence IDs, language settings, and user information.
     *
     * @param report the ReportRecord instance containing the details of the report; must not be null.
     * @param evidenceIds a list of evidence IDs associated with the report; must not be null.
     * @param lm the LanguageManager instance used to retrieve localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     * @param reportedEntity the name or identifier of the entity reported in the context of the report; must not be null.
     * @param submittedBy the identifier of the user who submitted the report; must not be null.
     * @return a string containing the localized HTML content for the report notification.
     */
    private static String builderHtml(ReportRecord report, List<String> evidenceIds, LanguageManager lm,
                                      Language lang, String reportedEntity, String submittedBy)
    {
        StringBuilder html = new StringBuilder(lm.get(lang, "report_notification", "header"));
        html.append(lm.get(lang, "report_notification", "report_id", HtmlEscape.escape(report.uuid()))).append('\n');
        html.append(lm.get(lang, "report_notification", "incident_type",
                HtmlEscape.escape(incidentType(lm, lang, report)))).append('\n');
        html.append(lm.get(lang, "report_notification", "reported_entity", reportedEntity)).append('\n');
        html.append(lm.get(lang, "report_notification", "submitted_by", submittedBy)).append('\n');
        html.append(lm.get(lang, "report_notification", "created", report.created())).append('\n');
        appendReportMessage(html, report, lm, lang);
        appendEvidenceIds(html, evidenceIds, lm, lang);
        html.append(lm.get(lang, "report_notification", "footer"));
        return html.toString();
    }

    /**
     * Appends a formatted report message to the provided HTML content.
     * The message is retrieved from the report record and localized using the language manager.
     * If the report message is null or blank, a fallback localized message is appended instead.
     *
     * @param html the StringBuilder used to construct the HTML content; must not be null.
     * @param report the ReportRecord instance containing the report details; must not be null.
     * @param lm the LanguageManager instance used for retrieving localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     */
    private static void appendReportMessage(StringBuilder html, ReportRecord report, LanguageManager lm, Language lang)
    {
        String message = report.message();
        html.append(lm.get(lang, "report_notification", "message", message == null || message.isBlank()
                ? lm.get(lang, "report_notification", "message_missing")
                : HtmlEscape.escape(truncate(message)))).append('\n');
    }

    /**
     * Appends a list of evidence IDs to the provided HTML content.
     * This method adds evidence section text and formats each evidence ID into
     * a localized evidence entry, which is then appended to the HTML.
     * If the evidenceIds list is empty, the method returns without modifying the HTML.
     *
     * @param html the StringBuilder used to construct the HTML content; must not be null.
     * @param evidenceIds a list of evidence IDs to be appended to the HTML; must not be null.
     * @param lm the LanguageManager instance used for retrieving localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     */
    private static void appendEvidenceIds(StringBuilder html, List<String> evidenceIds, LanguageManager lm, Language lang)
    {
        if (evidenceIds.isEmpty())
        {
            return;
        }

        html.append(lm.get(lang, "report_notification", "evidence"));
        for (String id : evidenceIds)
        {
            html.append(lm.get(lang, "report_notification", "evidence_entry", HtmlEscape.escape(id))).append('\n');
        }
    }

    /**
     * Loads the evidence records associated with a specific report.
     *
     * @param federation the FederationService instance used to fetch evidence records; must not be null.
     * @param reportUuid the UUID of the report whose evidence is loaded; must not be null.
     * @return the report's evidence records, or an empty list if an error occurs.
     */
    private static List<EvidenceRecord> loadEvidence(FederationService federation, String reportUuid)
    {
        try
        {
            return federation.reportEvidence(reportUuid, EVIDENCE_PAGE_SIZE);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to load evidence for report {}: {}", reportUuid, e.getMessage());
            return List.of();
        }
    }

    /**
     * Determines the incident type for a given report and returns its formatted name.
     * If the incident type is undefined, a localized fallback is provided.
     *
     * @param lm the LanguageManager instance used to retrieve localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     * @param report the ReportRecord instance containing report details; must not be null.
     * @return the formatted name of the incident type if defined; otherwise, a localized "unknown" text.
     */
    private static String incidentType(LanguageManager lm, Language lang, ReportRecord report)
    {
        return report.incidentType() == null ? lm.get(lang, "general", "unknown") : report.incidentType().name().replace('_', ' ');
    }

    /**
     * Truncates the given string to ensure its length does not exceed the maximum allowed length.
     * If the string's length exceeds the maximum, it is truncated and an ellipsis ("…") is appended.
     *
     * @param value the input string to be truncated; must not be null.
     * @return the original string if its length is within the allowed limit,
     *         or a truncated version with an ellipsis appended if it exceeds the limit.
     */
    private static String truncate(String value)
    {
        if (value.length() <= MAX_MESSAGE_TEXT_LENGTH)
        {
            return value;
        }

        return value.substring(0, MAX_MESSAGE_TEXT_LENGTH - 1) + "…";
    }
}
