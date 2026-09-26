package net.nosial.spb.classes.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the callback data carried by notification buttons, which must stay within Telegram's
 * limit and carry everything the action needs, since no server-side state backs them.
 */
class NotificationCallbackTest
{
    /** Telegram rejects inline buttons whose callback data exceeds this many bytes. */
    private static final int TELEGRAM_CALLBACK_DATA_LIMIT = 64;

    @Test
    @DisplayName("moderation button data fits Telegram's limit even with the largest ids")
    void reportActionFitsLimit()
    {
        for (ReportActionCallback.Action action : ReportActionCallback.Action.values())
        {
            String data = new ReportActionCallback(Long.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, action).data();
            assertTrue(data.getBytes(StandardCharsets.UTF_8).length <= TELEGRAM_CALLBACK_DATA_LIMIT, data);
        }
    }

    @Test
    @DisplayName("moderation button data parses back to the same message and action")
    void reportActionRoundTrips()
    {
        ReportActionCallback button = new ReportActionCallback(-1001234567890L, 4567, 7_000_000_001L, ReportActionCallback.Action.MUTE);
        assertEquals(button, ReportActionCallback.parse(button.data()));
    }

    @Test
    @DisplayName("legacy and malformed moderation button data is not mistaken for a current button")
    void reportActionRejectsForeignData()
    {
        assertNull(ReportActionCallback.parse("report_action:0b6f3c2e-8d7c-4a53-9d7e-2f1f5c9a1b2c:ban"));
        assertNull(ReportActionCallback.parse("rpa:-100123:45:67:explode"));
        assertNull(ReportActionCallback.parse("rpa:-100123:notanumber:67:ban"));
    }

    @Test
    @DisplayName("operator report button data fits Telegram's limit for every action")
    void operatorReportFitsLimit()
    {
        String uuid = "0b6f3c2e-8d7c-4a53-9d7e-2f1f5c9a1b2c";
        for (String action : new String[]{OperatorReportCallback.CLOSE, "NORMAL", "SUSPICIOUS", "MALICIOUS"})
        {
            String data = OperatorReportCallback.data(uuid, action);
            assertTrue(data.getBytes(StandardCharsets.UTF_8).length <= TELEGRAM_CALLBACK_DATA_LIMIT, data);
        }
    }
}
