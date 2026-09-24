package net.nosial.spb.handlers.operators;

import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.exceptions.ArgumentParseException;
import net.nosial.spb.classes.ReportSubmissionService;
import net.nosial.spb.utilities.IncidentTypes;
import net.nosial.spb.utilities.ReportAttachments;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.context.ReportContext;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageContent;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the {@code /blacklist} command, intended for authenticated Federation operators.
 *
 * <p>The command blacklists an entity on the Federation server, backed by a supporting report
 * record. It accepts several forms:
 * <ul>
 *     <li>{@code /blacklist <identifier> <report_uuid> <type> <expires>} — blacklist the given
 *     entity against an existing report uuid,</li>
 *     <li>{@code /blacklist <report_uuid> <type> <expires>} as a reply to a message — blacklist
 *     the replied-to message's author against an existing report uuid,</li>
 *     <li>{@code /blacklist <type> <expires>} as a reply to a message — create a supporting report
 *     for the replied-to message's author first (exactly like {@code /report}, but without a
 *     moderator notification), then blacklist the author against that freshly created report.</li>
 * </ul>
 *
 * <p>The {@code expires} argument is a duration with a unit suffix ({@code s}, {@code m},
 * {@code h} or {@code d}; plain numbers default to seconds), such as {@code 30d}, {@code 12h} or
 * {@code 45m}. The resulting expiration timestamp is the current time plus that duration.
 *
 * <p>Only a Telegram user with stored operator credentials ({@code /auth}) may invoke the command.
 * The Federation calls run as the calling operator rather than as the bot, so the resulting
 * token, so the blacklist (and any reference report) is attributed to that operator rather than to
 * the bot's shared client.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "blacklist")
public final class BlacklistHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BlacklistHandler.class);
    private static final String TELEGRAM_ENTITY_SUFFIX = "@telegram.org";

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern EXPIRES_PATTERN = Pattern.compile("^(\\d+)(s|m|h|d)?$", Pattern.CASE_INSENSITIVE);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        Language lang = resolveLanguage(context, message);
        LanguageManager lm = context.languages();

        if (!context.federation().isAvailable())
        {
            sendHtml(context, message, lm.get(lang, "blacklist", "federation_not_configured"), true, null);
            return;
        }

        OperatorIdentity operator = context.managers().operators().getOperator(message.getFrom().getId()).orElse(null);
        if (operator == null)
        {
            sendHtml(context, message, lm.get(lang, "blacklist", "operator_only"), true, null);
            return;
        }

        String[] args = MessageHelper.parseArguments(message.getText());
        BlacklistRequest parsed;
        try
        {
            parsed = parseArguments(context, message, args, lang);
        }
        catch (ArgumentParseException e)
        {
            sendHtml(context, message, e.message(), true, null);
            return;
        }

        try
        {
            String reportUuid = parsed.reportUuid();
            if (reportUuid == null)
            {
                reportUuid = createReferenceReport(context, operator.accessToken(), message, parsed);
                if (reportUuid == null)
                {
                    sendHtml(context, message, lm.get(lang, "blacklist", "report_failed"), true, null);
                    return;
                }
            }

            long nowSeconds = System.currentTimeMillis() / 1000;
            int expires = (int) (nowSeconds + parsed.expirationSeconds());
            String blacklistUuid;
            try
            {
                blacklistUuid = context.federation().blacklistEntity(operator.accessToken(),
                        parsed.entityIdentifier(), reportUuid, parsed.incidentType(), expires);
            }
            catch (FederationException e)
            {
                LOGGER.warn("Federation rejected blacklisting of {} for operator {}: {}",
                        parsed.entityIdentifier(), message.getFrom().getId(), e.getMessage());
                sendHtml(context, message, lm.get(lang, "blacklist", "blacklist_failed"), true, null);
                return;
            }

            String html = lm.get(lang, "blacklist", "success_header")
                    + lm.get(lang, "blacklist", "entity", HtmlEscape.escape(parsed.entityIdentifier())) + "\n"
                    + lm.get(lang, "blacklist", "report_id", HtmlEscape.escape(reportUuid)) + "\n"
                    + lm.get(lang, "blacklist", "type",
                    HtmlEscape.escape(MessageHelper.displayName(parsed.incidentType()))) + "\n"
                    + lm.get(lang, "blacklist", "expires",
                    HtmlEscape.escape(MessageHelper.formatTimestamp(expires))) + "\n"
                    + lm.get(lang, "blacklist", "blacklist_id", HtmlEscape.escape(blacklistUuid));
            sendHtml(context, message, html, true, null);
        }
        catch (IllegalArgumentException e)
        {
            LOGGER.warn("Federation blacklist operation failed for operator {}: {}", message.getFrom().getId(), e.getMessage());
            sendHtml(context, message, lm.get(lang, "blacklist", "unavailable"), true, null);
        }
    }

    /**
     * Creates a reference report based on the provided context, access token, message, and parsed blacklist request.
     * This method processes the target message, constructs a reporting context, and submits the report to the
     * appropriate service.
     *
     * @param context the per-update command context used for handling the report submission process
     * @param accessToken the access token used for authenticating the report submission to the external service
     * @param message the message that triggers the creation of the reference report
     * @param parsed the parsed blacklist request containing information about the incident type and other metadata
     * @return the response from the report submission service as a string
     * @throws TelegramApiException if there is an issue during the report creation or submission process
     */
    private static String createReferenceReport(HandlerContext context, String accessToken, Message message, BlacklistRequest parsed) throws TelegramApiException
    {
        Message targetMessage = replyTarget(message);
        User targetAuthor = targetMessage.getFrom();
        long operatorId = message.getFrom().getId();

        List<ReportAttachment> attachments = ReportAttachments.forReport(context, targetMessage);
        ReportContext report = new ReportContext(null, operatorId, message.getChatId(),
                targetMessage.getMessageId(), targetAuthor.getId(),
                MessageContent.textOrCaption(targetMessage), attachments, FlatMetadata.of(targetMessage), null, true,
                targetMessage.getMessageThreadId(), null, parsed.incidentType(), true,
                System.currentTimeMillis(), System.currentTimeMillis());

        return ReportSubmissionService.submitOperatorReference(context, accessToken, report);
    }

    /**
     * Parses and validates the {@code /blacklist} arguments, resolving the entity identifier and
     * the report UUID from either the explicit arguments or the replied-to message.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param args the whitespace-split arguments following the command
     * @param lang the resolved language
     * @return the fully resolved arguments, or throws when they are invalid
     */
    private static BlacklistRequest parseArguments(HandlerContext context, Message message, String[] args,
                                                  Language lang) throws ArgumentParseException
    {
        LanguageManager lm = context.languages();

        if (args.length < 2 || args.length > 4)
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "usage"));
        }

        int typeIndex = args.length - 2;
        IncidentType incidentType = IncidentTypes.parse(args[typeIndex]);
        if (incidentType == null)
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "invalid_type", args[typeIndex]));
        }

        long expirationSeconds = parseExpirationSeconds(args[args.length - 1]);
        if (expirationSeconds <= 0)
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "invalid_expires", args[args.length - 1]));
        }

        String entityIdentifier = null;
        String reportUuid = null;
        Message targetMessage = replyTarget(message);

        if (args.length == 4)
        {
            entityIdentifier = resolveIdentifier(context, args[0], lang);
        }
        else
        {
            targetMessage = requireReplyTarget(context, message, targetMessage, lang);
            if (args.length == 3)
            {
                reportUuid = args[0];
                if (!MessageHelper.isUuid(reportUuid))
                {
                    throw new ArgumentParseException(lm.get(lang, "blacklist", "invalid_report", reportUuid));
                }
            }
        }

        if (entityIdentifier == null)
        {
            long authorId = targetMessage.getFrom().getId();
            entityIdentifier = authorId + TELEGRAM_ENTITY_SUFFIX;
        }

        return new BlacklistRequest(entityIdentifier, reportUuid, incidentType, expirationSeconds);
    }

    /**
     * Validates and returns the target message that a blacklist command is replying to. This method checks
     * if the command is being used in a group chat, whether the target message is suitable for reporting,
     * and ensures that certain conditions (e.g., not allowing self-reports or reports against administrators)
     * are met before returning the target message.
     *
     * @param context the per-update command context used to access services and perform validations
     * @param message the incoming message that triggers the blacklist command
     * @param targetMessage the message being replied to, which is the intended target for the blacklist action
     * @param lang the resolved language for retrieving localized exception messages
     * @return the target message to be processed by the blacklist command
     * @throws ArgumentParseException if the blacklist command is misused or if the target message cannot be processed
     */
    private static Message requireReplyTarget(HandlerContext context, Message message, Message targetMessage, Language lang) throws ArgumentParseException
    {
        LanguageManager lm = context.languages();

        if (!isGroupChat(message))
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "usage"));
        }
        if (targetMessage == null || targetMessage.getFrom() == null)
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "cannot_be_reported"));
        }
        if (targetMessage.getFrom().getId().longValue() == message.getFrom().getId().longValue())
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "self_report"));
        }
        if (isChatAdministrator(context, message.getChatId(), targetMessage.getFrom().getId()))
        {
            throw new ArgumentParseException(lm.get(lang, "blacklist", "admin_report"));
        }
        return targetMessage;
    }

    /**
     * Determines the target message to which the given message is replying. If the message
     * is not a reply or if it is determined to be a reply to a topic header, it returns null.
     *
     * @param message the message for which the reply target is being processed
     * @return the target message being replied to, or null if no valid reply target exists
     */
    private static Message replyTarget(Message message)
    {
        Message replyTo = message.getReplyToMessage();
        if (replyTo == null || MessageHelper.isReplyToTopicHeader(message))
        {
            return null;
        }
        return replyTo;
    }

    /**
     * Resolves the provided identifier string into a valid entity representation based on the context and language settings.
     * The method handles several formats such as UUIDs, SHA-256 hashes, usernames (with or without '@' prefix),
     * numeric identifiers, and entity suffixes.
     *
     * @param context the per-update command context used for resolving the identifier
     * @param argument the identifier string to be resolved, which may represent a UUID, username, or numeric ID
     * @param lang the language setting used for resolving localized information when necessary
     * @return the resolved identifier as a string, or null if the input is blank or invalid
     */
    private static String resolveIdentifier(HandlerContext context, String argument, Language lang)
    {
        if (argument == null || argument.isBlank())
        {
            return null;
        }
        if (UUID_PATTERN.matcher(argument).matches() || SHA256_PATTERN.matcher(argument).matches())
        {
            return argument;
        }
        if (argument.contains("@"))
        {
            return argument;
        }
        if (argument.startsWith("@"))
        {
            return resolveUsername(context, argument.substring(1), lang);
        }
        if (NUMERIC_PATTERN.matcher(argument).matches())
        {
            return argument + TELEGRAM_ENTITY_SUFFIX;
        }
        return resolveUsername(context, argument, lang);
    }

    /**
     * Resolves a username and maps it to a user identifier in the current context, appending
     * the appropriate Telegram entity suffix if applicable. The method first normalizes the provided
     * username and checks for a local user ID. If no local user information is found, the method
     * queries the Telegram API to resolve the username to a possible chat identifier.
     *
     * @param context the per-update handler context, used for accessing user management and external APIs
     * @param username the username to resolve, which may contain an optional "@" prefix
     * @param lang the language setting for localized error handling, if needed
     * @return the resolved user identifier as a string with the Telegram entity suffix appended, or {@code null} if the resolution fails
     */
    private static String resolveUsername(HandlerContext context, String username, Language lang)
    {
        String normalized = normalizeUsername(username);
        if (normalized == null)
        {
            return null;
        }
        Long userId = context.managers().users().getUserIdByUsername(normalized).orElse(null);
        if (userId == null)
        {
            try
            {
                ChatFullInfo chat = context.telegramClient().execute(
                        GetChat.builder().chatId("@" + normalized).build());
                if (chat != null)
                {
                    userId = chat.getId();
                }
            }
            catch (TelegramApiException e)
            {
                LOGGER.debug("Failed to resolve username @{} through the Telegram API: {}", normalized, e.getMessage());
            }
        }
        return userId == null ? null : userId + TELEGRAM_ENTITY_SUFFIX;
    }

    /**
     * Normalizes the provided username by removing leading and trailing whitespace,
     * stripping the "@" prefix if present, and converting it to lowercase.
     * Returns null if the input username is null, blank, or results in an empty
     * string after normalization.
     *
     * @param username the username string to be normalized
     * @return the normalized username string, or null if the input is null, blank,
     *         or becomes empty after normalization
     */
    private static String normalizeUsername(String username)
    {
        if (username == null || username.isBlank())
        {
            return null;
        }
        String normalized = username.trim();
        if (normalized.startsWith("@"))
        {
            normalized = normalized.substring(1);
        }
        normalized = normalized.toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Parses a token string to determine the expiration time in seconds.
     * The token should consist of a numeric value and an optional suffix
     * that specifies the time unit (e.g., "m" for minutes, "h" for hours, "d" for days).
     * If the token is invalid or the calculated expiration time is non-positive,
     * the method returns -1.
     *
     * @param token the input string representing the expiration time, potentially
     *              containing a numeric value followed by a time unit suffix.
     * @return the parsed expiration time in seconds, or -1 if the token is null,
     *         invalid, or results in a non-positive value.
     */
    static long parseExpirationSeconds(String token)
    {
        if (token == null)
        {
            return -1;
        }
        Matcher matcher = EXPIRES_PATTERN.matcher(token.trim());
        if (!matcher.matches())
        {
            return -1;
        }
        try
        {
            long value = Long.parseLong(matcher.group(1));
            String suffix = matcher.group(2);
            long seconds = value;
            if (suffix != null)
            {
                seconds = switch (suffix.toLowerCase(Locale.ROOT))
                {
                    case "m" -> Math.multiplyExact(value, 60);
                    case "h" -> Math.multiplyExact(value, 3600);
                    case "d" -> Math.multiplyExact(value, 86400);
                    default -> value;
                };
            }
            return seconds > 0 ? seconds : -1;
        }
        catch (ArithmeticException e)
        {
            return -1;
        }
    }

    /**
     * The fully resolved set of arguments for a blacklist operation.
     *
     * @param entityIdentifier the entity to blacklist
     * @param reportUuid the supporting report UUID, or {@code null} to create one
     * @param incidentType the incident type of the blacklist
     * @param expirationSeconds the expiration duration in seconds, added to the current time
     */
    private record BlacklistRequest(String entityIdentifier, String reportUuid, IncidentType incidentType, long expirationSeconds)
    {
    }
}
