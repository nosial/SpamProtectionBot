package net.nosial.spb.utilities;

import java.util.regex.Pattern;

/**
 * Escapes HTML-sensitive characters so resolved strings can be embedded in Telegram HTML messages,
 * and turns Telegram HTML back into plain text for places that cannot render it.
 */
public final class HtmlEscape
{
    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    /**
     * Escapes {@code &}, {@code <}, and {@code >} in the given value.
     *
     * @param value the raw string
     * @return the escaped string, or an empty string when the input is {@code null}
     */
    public static String escape(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Converts Telegram HTML to the plain text it displays: tags are removed and the entities
     * Telegram supports are decoded. Used for surfaces Telegram always shows as plain text, such as
     * callback query alerts and toasts.
     *
     * @param html the HTML text
     * @return the plain text, or an empty string when the input is {@code null}
     */
    public static String toPlainText(String html)
    {
        if (html == null)
        {
            return "";
        }
        return TAG.matcher(html).replaceAll("")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
