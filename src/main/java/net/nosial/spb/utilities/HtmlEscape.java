package net.nosial.spb.utilities;

/**
 * Escapes HTML-sensitive characters so resolved strings can be embedded in Telegram HTML messages.
 */
public final class HtmlEscape
{
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
}
