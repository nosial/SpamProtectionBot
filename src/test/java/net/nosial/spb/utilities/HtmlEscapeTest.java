package net.nosial.spb.utilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for turning Telegram HTML into the plain text shown in callback alerts and toasts.
 */
class HtmlEscapeTest
{
    @Test
    @DisplayName("HTML tags are removed and entities decoded")
    void convertsHtmlToPlainText()
    {
        assertEquals("Report Action Expired\n\nUse /auth <access_token> first.",
                HtmlEscape.toPlainText("<b>Report Action Expired</b>\n\nUse <code>/auth &lt;access_token&gt;</code> first."));
    }

    @Test
    @DisplayName("text that merely contains angle brackets is left intact")
    void keepsLiteralAngleBrackets()
    {
        assertEquals("1 < 2 and 3 > 2", HtmlEscape.toPlainText("1 < 2 and 3 > 2"));
    }
}
