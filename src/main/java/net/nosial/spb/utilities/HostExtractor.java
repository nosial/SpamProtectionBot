package net.nosial.spb.utilities;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the domain names and IP addresses mentioned in a message's text or caption.
 *
 * <p>Hosts are gathered from three places, in this order:
 *
 * <ol>
 *   <li>the links Telegram itself marked up: {@code url} entities, and the targets of
 *       {@code text_link} entities, which are not visible in the text at all;</li>
 *   <li>{@code scheme://} URLs written in the text;</li>
 *   <li>bare domain names and IP addresses in the rest of the text.</li>
 * </ol>
 *
 * <p>Every candidate is canonicalized by {@link HostCanonicalizer}, which rejects anything
 * malformed or non-public, and the result is de-duplicated and capped. Email addresses and
 * {@code @mentions} are deliberately skipped: they name an account, not a host. The text is
 * untrusted and may be arbitrarily malformed; nothing here throws on bad input, and the regular
 * expressions used run in linear time.
 *
 * <p>All methods are stateless and thread-safe.
 */
public final class HostExtractor
{
    /** The most hosts taken from a single message, bounding the Federation calls it can cause. */
    public static final int MAX_HOSTS_PER_MESSAGE = 20;

    /**
     * A {@code scheme://} URL. It runs until whitespace or a character that cannot appear
     * unescaped in a link as written in prose (quotes, angle brackets, CJK punctuation).
     */
    private static final Pattern SCHEME_URL = Pattern.compile(
            "(?<![A-Za-z0-9+.\\-])[A-Za-z][A-Za-z0-9+.\\-]{0,31}://[^\\s\\p{Z}<>\"'`，、；：！？「」『』（）【】《》]+");

    /** A run that may hold a domain name or IPv4 address; boundaries are checked in code. */
    private static final Pattern DOMAIN_OR_IPV4_RUN = Pattern.compile("[A-Za-z0-9.\\-]+");

    /** A run that may hold an IPv6 address; boundaries are checked in code. */
    private static final Pattern IPV6_RUN = Pattern.compile("[0-9A-Fa-f:.]+");

    /** Characters a sentence may put right after a link without them being part of it. */
    private static final String TRAILING_PUNCTUATION = ".,;:!?'\")]}>»”’…";

    /**
     * Returns the hosts mentioned in a message's text, or in its caption when it has no text.
     *
     * @param message the Telegram message
     * @return the canonical hosts in order of appearance, at most {@link #MAX_HOSTS_PER_MESSAGE}
     */
    public static List<String> extract(Message message)
    {
        if (message == null)
        {
            return List.of();
        }
        if (message.getText() != null)
        {
            return extract(message.getText(), message.getEntities(), MAX_HOSTS_PER_MESSAGE);
        }
        return extract(message.getCaption(), message.getCaptionEntities(), MAX_HOSTS_PER_MESSAGE);
    }

    /**
     * Returns the hosts mentioned in a text.
     *
     * @param text the text, or {@code null}
     * @param entities the Telegram entities of that text, or {@code null}
     * @param limit the most hosts to return
     * @return the canonical hosts in order of appearance, never {@code null}
     */
    public static List<String> extract(String text, List<MessageEntity> entities, int limit)
    {
        if (limit <= 0 || ((text == null || text.isEmpty()) && (entities == null || entities.isEmpty())))
        {
            return List.of();
        }

        Set<String> hosts = new LinkedHashSet<>();
        String source = text != null ? text : "";
        // Regions already understood (links, emails) are blanked so the bare-word pass does not
        // pick apart their paths and local parts: "https://a.com/notes.md" must not yield notes.md.
        char[] remaining = source.toCharArray();

        if (entities != null)
        {
            for (MessageEntity entity : entities)
            {
                if (entity == null || entity.getType() == null)
                {
                    continue;
                }
                switch (entity.getType())
                {
                    case "text_link" -> add(hosts, HostCanonicalizer.fromUrl(entity.getUrl()), limit);
                    case "url" ->
                    {
                        String link = slice(source, entity);
                        if (link != null)
                        {
                            add(hosts, HostCanonicalizer.fromUrl(link), limit);
                            blank(remaining, entity.getOffset(), entity.getOffset() + entity.getLength());
                        }
                    }
                    case "email" ->
                    {
                        if (slice(source, entity) != null)
                        {
                            blank(remaining, entity.getOffset(), entity.getOffset() + entity.getLength());
                        }
                    }
                    default ->
                    {
                    }
                }
            }
        }

        Matcher url = SCHEME_URL.matcher(new String(remaining));
        while (url.find())
        {
            int end = trimTrailingPunctuation(remaining, url.start(), url.end());
            add(hosts, HostCanonicalizer.fromUrl(new String(remaining, url.start(), end - url.start())), limit);
            blank(remaining, url.start(), url.end());
        }

        String rest = new String(remaining);
        Matcher run = DOMAIN_OR_IPV4_RUN.matcher(rest);
        while (run.find())
        {
            int start = run.start();
            int end = run.end();
            while (start < end && (rest.charAt(start) == '.' || rest.charAt(start) == '-'))
            {
                start++;
            }
            while (end > start && (rest.charAt(end - 1) == '.' || rest.charAt(end - 1) == '-'))
            {
                end--;
            }
            String candidate = rest.substring(start, end);
            if (candidate.indexOf('.') < 0 || !standsAlone(rest, run.start(), run.end(), "@/\\:_", "@_"))
            {
                continue;
            }
            add(hosts, HostCanonicalizer.fromText(candidate), limit);
        }

        Matcher ipv6 = IPV6_RUN.matcher(rest);
        while (ipv6.find())
        {
            int start = ipv6.start();
            int end = ipv6.end();
            while (end > start && rest.charAt(end - 1) == '.')
            {
                end--;
            }
            if (countColons(rest, start, end) < 2 || !standsAlone(rest, start, ipv6.end(), "/\\_", "_"))
            {
                continue;
            }
            add(hosts, HostCanonicalizer.fromText(rest.substring(start, end)), limit);
        }

        return List.copyOf(hosts);
    }

    /**
     * Extracts a substring from the given text based on the offset and length provided
     * by a {@link MessageEntity}.
     *
     * @param text the original text from which the substring is to be extracted
     * @param entity the {@link MessageEntity} containing the offset and length defining the substring
     * @return the extracted substring if the offset and length are valid, or {@code null} otherwise
     */
    private static String slice(String text, MessageEntity entity)
    {
        Integer offset = entity.getOffset();
        Integer length = entity.getLength();
        if (offset == null || length == null || offset < 0 || length <= 0 || (long) offset + length > text.length())
        {
            return null;
        }
        return text.substring(offset, offset + length);
    }

    /**
     * Determines if a substring within a given text stands alone, meaning it is not immediately
     * preceded or followed by specific forbidden characters or alphanumeric characters.
     *
     * @param text the complete text to evaluate
     * @param start the starting index of the substring
     * @param end the ending index of the substring
     * @param forbiddenBefore characters that are not allowed immediately before the substring
     * @param forbiddenAfter characters that are not allowed immediately after the substring
     * @return {@code true} if the substring is considered to stand alone, otherwise {@code false}
     */
    private static boolean standsAlone(String text, int start, int end, String forbiddenBefore, String forbiddenAfter)
    {
        if (start > 0)
        {
            char before = text.charAt(start - 1);
            if (Character.isLetterOrDigit(before) || forbiddenBefore.indexOf(before) >= 0)
            {
                return false;
            }
        }

        if (end < text.length())
        {
            char after = text.charAt(end);
            return !Character.isLetterOrDigit(after) && forbiddenAfter.indexOf(after) < 0;
        }

        return true;
    }

    /**
     * Trims trailing punctuation characters from a given range of characters in an array while ensuring
     * proper bracket balancing. This method evaluates trailing punctuation based on a predefined set
     * of characters and stops trimming when the balances of opening and closing brackets are consistent.
     *
     * @param text the array of characters to process
     * @param start the starting index of the range to evaluate
     * @param end the ending index of the range to evaluate
     * @return the new end index after trimming trailing punctuation, ensuring bracket balance
     */
    private static int trimTrailingPunctuation(char[] text, int start, int end)
    {
        // Bracket balance is counted once and kept current while trimming, so a URL followed by
        // thousands of closing brackets costs linear time, not quadratic.
        String brackets = "()[]{}";
        int[] counts = new int[brackets.length()];
        for (int i = start; i < end; i++)
        {
            int index = brackets.indexOf(text[i]);
            if (index >= 0)
            {
                counts[index]++;
            }
        }

        while (end > start && TRAILING_PUNCTUATION.indexOf(text[end - 1]) >= 0)
        {
            int closing = brackets.indexOf(text[end - 1]);
            if (closing % 2 == 1 && counts[closing - 1] >= counts[closing])
            {
                break;
            }
            if (closing >= 0)
            {
                counts[closing]--;
            }
            end--;
        }
        return end;
    }

    /**
     * Counts the number of colon (:) characters within a specified substring of the given text.
     *
     * @param text the text in which to count colons; must not be null
     * @param start the starting index (inclusive) of the substring to check
     * @param end the ending index (exclusive) of the substring to check
     * @return the number of colon characters found within the specified range
     */
    private static int countColons(String text, int start, int end)
    {
        int count = 0;
        for (int i = start; i < end; i++)
        {
            if (text.charAt(i) == ':')
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Replaces all characters in the specified range of the given array with a blank space (' ').
     *
     * @param text the array of characters to modify
     * @param start the starting index (inclusive) of the range to be blanked; values less than 0 are treated as 0
     * @param end the ending index (exclusive) of the range to be blanked; values greater than the array's length are capped at the array's length
     */
    private static void blank(char[] text, int start, int end)
    {
        for (int i = Math.max(0, start); i < Math.min(text.length, end); i++)
        {
            text[i] = ' ';
        }
    }

    /**
     * Adds a host to the provided set of hosts if the set's size does not exceed the specified limit
     * and the host is present.
     *
     * @param hosts the set of hosts to which the host will be added
     * @param host  an optional host to add to the set
     * @param limit the maximum number of hosts allowed in the set
     */
    private static void add(Set<String> hosts, Optional<String> host, int limit)
    {
        if (hosts.size() < limit)
        {
            host.ifPresent(hosts::add);
        }
    }
}
