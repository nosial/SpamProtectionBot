package net.nosial.spb.utilities;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationActionsTest
{
    @Test
    void untilFromNowIsAnAbsoluteTimeInTheFuture()
    {
        long before = Instant.now().getEpochSecond();
        long until = ModerationActions.untilFromNow(3_600L);
        long after = Instant.now().getEpochSecond();

        // Telegram's until_date is absolute: a bare 3600 would mean 1970, which Telegram treats as
        // "forever" and turns a one-hour mute into a permanent restriction.
        assertTrue(until >= before + 3_600L && until <= after + 3_600L, "until=" + until);
    }

    @Test
    void temporaryRestrictionDefaultsToADayFromNow()
    {
        long now = Instant.now().getEpochSecond();
        long until = ModerationActions.temporaryRestrictionUntil(null);
        assertTrue(until >= now + 86_400L && until <= now + 86_401L, "until=" + until);

        // A suggested lift time too close to now is not honoured by Telegram, so the default applies.
        long tooSoon = ModerationActions.temporaryRestrictionUntil(now + 5);
        assertTrue(tooSoon >= now + 86_400L, "until=" + tooSoon);

        long suggested = now + 7_200L;
        assertTrue(ModerationActions.temporaryRestrictionUntil(suggested) == suggested);
    }
}
