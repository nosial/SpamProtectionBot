package net.nosial.spb.classes.sessions;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportActionState;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Remembers, per reported message, which report notifications carry moderation buttons and
 * whether a moderator has already acted on them.
 *
 * <p>This state is an optimisation, not a requirement: the buttons carry everything the action
 * needs (see {@link net.nosial.spb.classes.notifications.ReportActionCallback}), so they keep
 * working after the state expires or the bot restarts. What the state adds is that the first
 * press wins, so the message is not deleted or its author banned twice, and that the buttons are
 * removed from every moderator's copy of the notification, not only the one pressed.
 */
public final class ReportActionTracker
{
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long EXPIRY_DAYS = 7;

    private final Cache<String, ReportActionState> states;

    public ReportActionTracker()
    {
        this.states = Cache.create(CACHE_MAX_SIZE, EXPIRY_DAYS, TimeUnit.DAYS);
    }

    /**
     * Records the notifications delivered for a report of the given message, alongside any
     * delivered for earlier reports of it, since an action on either removes the message they are
     * all about.
     *
     * <p>A moderator may press a button before every copy has been delivered and recorded. The
     * copies are then still recorded, never replacing the claim, and {@code false} tells the caller
     * to remove their buttons itself, since the moderator who acted may have missed them.
     *
     * @param chatId the protected chat the reported message lives in
     * @param messageId the reported message
     * @param targets the notifications that carry moderation buttons
     * @return {@code true} when the action is still open, {@code false} when a moderator already acted
     */
    public boolean track(long chatId, long messageId, List<NotificationTarget> targets)
    {
        ReportActionState state = state(chatId, messageId);
        state.addTargets(targets);
        return !state.isConsumed();
    }

    /**
     * Claims the moderation action on a reported message for the caller, exactly once.
     *
     * <p>A message that is not tracked, because its state expired or the bot restarted since the
     * notification was sent, is claimable too; the returned state then has no targets.
     *
     * @param chatId the protected chat the reported message lives in
     * @param messageId the reported message
     * @return the claimed state, or {@code null} when another moderator already acted
     */
    public ReportActionState claim(long chatId, long messageId)
    {
        ReportActionState state = state(chatId, messageId);
        return state.claim() ? state : null;
    }

    /**
     * Returns the state of a reported message, creating it atomically when absent, so a claim
     * and a concurrent {@link #track} always share one state.
     *
     * @param chatId the protected chat
     * @param messageId the reported message
     * @return the state
     */
    private ReportActionState state(long chatId, long messageId)
    {
        return this.states.get(chatId + ":" + messageId, ignored -> new ReportActionState());
    }
}
