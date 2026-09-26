package net.nosial.spb.classes.sessions;

import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportActionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that a reported message is acted on once, whichever moderator's copy is pressed.
 */
class ReportActionTrackerTest
{
    private static final long CHAT = -1001234567890L;

    @Test
    @DisplayName("only the first press on a reported message wins")
    void claimsOnce()
    {
        ReportActionTracker tracker = new ReportActionTracker();
        tracker.track(CHAT, 50, List.of(new NotificationTarget(1, 10)));

        ReportActionState first = tracker.claim(CHAT, 50);
        assertNotNull(first);
        assertEquals(1, first.targets().size());
        assertNull(tracker.claim(CHAT, 50));
    }

    @Test
    @DisplayName("a message whose state is gone, as after a restart, can still be acted on once")
    void untrackedMessageIsClaimableOnce()
    {
        ReportActionTracker tracker = new ReportActionTracker();

        ReportActionState first = tracker.claim(CHAT, 51);
        assertNotNull(first);
        assertEquals(List.of(), first.targets());
        assertNull(tracker.claim(CHAT, 51));
    }

    @Test
    @DisplayName("a press before every copy is delivered is not undone when the copies are recorded")
    void earlyPressSurvivesTracking()
    {
        ReportActionTracker tracker = new ReportActionTracker();

        assertNotNull(tracker.claim(CHAT, 53));
        assertFalse(tracker.track(CHAT, 53, List.of(new NotificationTarget(1, 10))));
        assertNull(tracker.claim(CHAT, 53));
    }

    @Test
    @DisplayName("a message reported twice removes the buttons of both reports' notifications")
    void mergesRepeatedReports()
    {
        ReportActionTracker tracker = new ReportActionTracker();
        tracker.track(CHAT, 52, List.of(new NotificationTarget(1, 10)));
        tracker.track(CHAT, 52, List.of(new NotificationTarget(2, 20)));

        assertEquals(2, tracker.claim(CHAT, 52).targets().size());
    }
}
