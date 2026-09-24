package net.nosial.spb.classes.notifications;

import net.nosial.spb.classes.interfaces.NotificationSink;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.managers.ManagerRegistry;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.sessions.OperatorReportSessionManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.OperatorReportContext;
import net.nosial.spb.utilities.EntityResolver;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Delivers a single-message report notification to an operator when a report is assigned to them.
 *
 * <p>The message begins with {@code #REPORT_ASSIGNED} and contains the report metadata, the
 * report message (if any), a list of evidence UUIDs, and the one-time operator action keyboard.
 */
final class ReportNotificationSender implements NotificationSink
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportNotificationSender.class);
    private static final int EVIDENCE_PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_TEXT_LENGTH = 3_500;

    private final OkHttpTelegramClient telegramClient;
    private final FederationService federation;
    private final ManagerRegistry managers;

    /**
     * Constructs a ReportNotificationSender object for handling the sending of report notifications.
     *
     * @param telegramClient the OkHttpTelegramClient instance used for sending messages via Telegram; must not be null.
     * @param federation the FederationService instance used for interacting with federated services; must not be null.
     * @param managers the ManagerRegistry instance used for accessing various manager components; must not be null.
     */
    ReportNotificationSender(OkHttpTelegramClient telegramClient, FederationService federation, ManagerRegistry managers)
    {
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
        this.federation = Objects.requireNonNull(federation, "federation must not be null");
        this.managers = Objects.requireNonNull(managers, "managers must not be null");
    }

    @Override
    public void send(long telegramUserId, ReportRecord report, OperatorReportContext session) throws Exception
    {
        Language lang = this.managers.languagePreferences().getUserLanguage(telegramUserId);
        List<String> evidenceIds = loadEvidenceIds(this.federation, report.uuid());

        this.telegramClient.execute(SendMessage.builder()
                .chatId(String.valueOf(telegramUserId))
                .text(detailsHtml(report, evidenceIds, lang))
                .parseMode(ParseMode.HTML)
                .replyMarkup(actionMarkup(NotificationFormatter.languageManager(), lang, session))
                .build());
    }

    /**
     * Generates an inline keyboard markup for action selection based on the provided session and language settings.
     *
     * @param lm the LanguageManager instance used for retrieving localized text; must not be null.
     * @param lang the Language object representing the user's language preference; must not be null.
     * @param session the OperatorReportContext object representing the context for operator report handling; must not be null.
     * @return an instance of InlineKeyboardMarkup containing the configured keyboard layout for actions.
     */
    static InlineKeyboardMarkup actionMarkup(LanguageManager lm, Language lang, OperatorReportContext session)
    {
        String prefix = OperatorReportSessionManager.CALLBACK_PREFIX + ":" + session.hash() + ":";
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close"))
                                .callbackData(prefix + "close").build(),
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_normal"))
                                .callbackData(prefix + "class:NORMAL").build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_suspicious"))
                                .callbackData(prefix + "class:SUSPICIOUS").build(),
                        InlineKeyboardButton.builder().text(lm.get(lang, "report_notification", "close_malicious"))
                                .callbackData(prefix + "class:MALICIOUS").build()))
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
     * Loads a list of evidence UUIDs associated with a specific report.
     * This method interacts with the FederationService to retrieve the evidence records
     * for a given report and extracts their UUIDs. If there is an error during the process,
     * an empty list is returned.
     *
     * @param federation the FederationService instance used to fetch evidence records; must not be null.
     * @param reportUuid the UUID of the report for which evidence IDs need to be loaded; must not be null.
     * @return a list of evidence UUIDs associated with the specified report, or an empty list if an error occurs.
     */
    private static List<String> loadEvidenceIds(FederationService federation, String reportUuid)
    {
        try
        {
            List<String> ids = new ArrayList<>();
            for (EvidenceRecord evidence : federation.reportEvidence(reportUuid, EVIDENCE_PAGE_SIZE))
            {
                ids.add(evidence.uuid());
            }
            return ids;
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
