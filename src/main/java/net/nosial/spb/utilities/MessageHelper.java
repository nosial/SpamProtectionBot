package net.nosial.spb.utilities;

import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared static utility methods for parsing message text, validating identifiers, formatting
 * timestamps, and producing human-readable labels from enum values.
 *
 * <p>This class is not instantiated; all methods are static.
 */
public final class MessageHelper
{
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageHelper.class);

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Returns whether a message is merely attached to its own forum-topic header rather than
     * replying to a specific message.
     *
     * <p>Within a forum topic every message is technically a reply to the message that opened the
     * thread, and Telegram reports that topic header as {@code reply_to_message} even when the user
     * did not explicitly reply to anything. Such an implicit attachment must not be treated as a
     * real reply target. A genuine reply to another user's message inside the topic reports that
     * user's message instead, whose id never matches the thread header id.
     *
     * @param message the incoming message
     * @return {@code true} when the message replies to its thread header rather than a specific
     *         message
     */
    public static boolean isReplyToTopicHeader(Message message)
    {
        Message replyTo = message.getReplyToMessage();
        if (replyTo == null || replyTo.getMessageId() == null || message.getMessageThreadId() == null)
        {
            return false;
        }
        return replyTo.getMessageId().equals(message.getMessageThreadId());
    }

    /**
     * Returns whether the given string is a valid UUID.
     *
     * @param value the string to test
     * @return {@code true} when the value matches the UUID pattern
     */
    public static boolean isUuid(String value)
    {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    /**
     * Formats a Unix epoch second timestamp as a human-readable UTC date-time string.
     *
     * @param epochSeconds the Unix epoch seconds
     * @return the formatted timestamp
     */
    public static String formatTimestamp(long epochSeconds)
    {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
    }

    /**
     * Returns the whitespace-split arguments following the command in the given text, or an
     * empty array when there are none.
     *
     * @param text the full command text
     * @return the arguments
     */
    public static String[] parseArguments(String text)
    {
        if (text == null)
        {
            return new String[0];
        }
        String trimmed = text.trim();
        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex == -1)
        {
            return new String[0];
        }
        String args = trimmed.substring(spaceIndex + 1).trim();
        if (args.isEmpty())
        {
            return new String[0];
        }
        return args.split("\\s+");
    }

    /**
     * Returns the payload that follows the leading command token, or {@code null} when the message
     * carries no payload.
     *
     * <p>The leading command token is stripped regardless of whether it was invoked as
     * {@code /command payload} or {@code /command@bot payload}: both forms place the payload after
     * the first whitespace, so the optional {@code @bot} suffix is handled implicitly. The payload
     * is returned trimmed and may itself contain whitespace.
     *
     * @param message the incoming command message
     * @return the payload, or {@code null} when there is none
     */
    public static String getMessagePayload(Message message)
    {
        String text = message.getText();
        if (text == null)
        {
            return null;
        }
        String trimmed = text.trim();
        int separator = firstWhitespace(trimmed);
        if (separator == -1)
        {
            return null;
        }
        String payload = trimmed.substring(separator + 1).trim();
        return payload.isEmpty() ? null : payload;
    }

    /**
     * Returns the payload following the command only when it is exactly one whitespace-separated
     * token, or {@code null} when there is no payload or the payload contains whitespace.
     *
     * @param message the incoming command message
     * @return the single-token payload, or {@code null}
     */
    public static String singleArgument(Message message)
    {
        String payload = getMessagePayload(message);
        if (payload == null || firstWhitespace(payload) != -1)
        {
            return null;
        }
        return payload;
    }

    /**
     * Returns the index of the first whitespace character in the given text, or {@code -1} when
     * there is none.
     *
     * @param text the text to scan
     * @return the whitespace index, or {@code -1}
     */
    public static int firstWhitespace(String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            if (Character.isWhitespace(text.charAt(i)))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts an upper-snake-case enum name into human-readable title case.
     *
     * @param value the enum value
     * @return the display text, or {@code "Unknown"} when {@code null}
     */
    public static String displayName(Enum<?> value)
    {
        if (value == null)
        {
            return "Unknown";
        }
        return humanize(value.name());
    }

    /**
     * Converts an upper-snake-case string into human-readable title case.
     *
     * @param value the upper-snake-case string
     * @return the display text
     */
    public static String humanize(String value)
    {
        String[] parts = value.toLowerCase().split("_");
        StringBuilder display = new StringBuilder();
        for (String part : parts)
        {
            if (!display.isEmpty())
            {
                display.append(' ');
            }
            if (!part.isEmpty())
            {
                display.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return display.toString();
    }

    /**
     * Creates an inline button that carries callback data.
     *
     * @param text the button label
     * @param callbackData the callback data sent when the button is pressed
     * @return the inline button
     */
    public static InlineKeyboardButton button(String text, String callbackData)
    {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    /**
     * Creates an inline button that opens an external URL.
     *
     * @param text the button label
     * @param url the button URL
     * @return the inline button
     */
    public static InlineKeyboardButton urlButton(String text, String url)
    {
        return InlineKeyboardButton.builder().text(text).url(url).build();
    }

    /**
     * Builds an inline keyboard from the given rows.
     *
     * @param rows the keyboard rows
     * @return the inline keyboard markup
     */
    public static InlineKeyboardMarkup markup(InlineKeyboardRow... rows)
    {
        return InlineKeyboardMarkup.builder().keyboard(List.of(rows)).build();
    }

    /**
     * Builds an inline keyboard whose buttons all sit on a single row.
     *
     * @param buttons the buttons of the single row
     * @return the inline keyboard markup
     */
    public static InlineKeyboardMarkup singleRowMarkup(InlineKeyboardButton... buttons)
    {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(buttons)).build();
    }

    /**
     * Arranges the given buttons into rows of at most two buttons each.
     *
     * @param buttons the buttons to arrange
     * @return the keyboard rows
     */
    public static List<InlineKeyboardRow> rowsOfTwo(List<InlineKeyboardButton> buttons)
    {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 2)
        {
            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(buttons.get(i));
            if (i + 1 < buttons.size())
            {
                row.add(buttons.get(i + 1));
            }
            rows.add(new InlineKeyboardRow(row));
        }
        return rows;
    }

    /**
     * Resolves the Federation server information from the client instance, or {@code null} when
     * federation is not configured or the server is unreachable.
     *
     * @param context the per-update command context
     * @return the server information, or {@code null}
     */
    public static ServerInformation serverInformation(HandlerContext context)
    {
        if (!context.federation().isAvailable())
        {
            return null;
        }

        try
        {
            return context.federation().serverInformation();
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to resolve Federation server information: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the Federation server endpoint as it is known to the client instance, guaranteed to
     * end with a trailing slash.
     *
     * @param context the per-update command context
     * @return the server host URL
     */
    public static String serverHost(HandlerContext context)
    {
        String endpoint = context.federation().endpoint();
        if (endpoint == null)
        {
            return "";
        }

        return endpoint.endsWith("/") ? endpoint : endpoint + "/";
    }

    /**
     * Logs the JSON payload of an outgoing API method at DEBUG level. When serialization fails,
     * the failure is logged but never propagated.
     *
     * @param context the command context
     * @param label a short label identifying the method (e.g. "open-main-menu")
     * @param method the method about to be executed
     */
    public static void logOutgoing(HandlerContext context, String label, Object method)
    {
        if (context.objectMapper() == null)
        {
            return;
        }
        try
        {
            LOGGER.debug("Outgoing {} payload: {}", label, context.objectMapper().writeValueAsString(method));
        }
        catch (Exception e)
        {
            LOGGER.debug("Failed to serialize outgoing {} payload: {}", label, e.getMessage());
        }
    }

    /**
     * Logs the JSON payload of a failed API method at ERROR level so the exact request that
     * Telegram rejected is captured in the logs.
     *
     * @param context the command context
     * @param label a short label identifying the method
     * @param method the method that failed
     * @param cause the Telegram API exception
     */
    public static void logFailed(HandlerContext context, String label, Object method, TelegramApiException cause)
    {
        if (context.objectMapper() == null)
        {
            LOGGER.error("{} failed: {}", label, cause.getMessage());
            return;
        }
        try
        {
            LOGGER.error("{} failed. Payload: {}", label,
                    context.objectMapper().writeValueAsString(method), cause);
        }
        catch (Exception serializationException)
        {
            LOGGER.error("{} failed: {} (could not serialize payload: {})",
                    label, cause.getMessage(), serializationException.getMessage(), cause);
        }
    }
}
