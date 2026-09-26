package net.nosial.spb.objects;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The moderation buttons attached to one report notification, and whether they have been used.
 *
 * <p>A report notification is delivered to every eligible moderator, so the same buttons exist in
 * several chats at once. The first moderator to press one acts; the rest must find the action
 * already taken rather than deleting the message twice or banning an already-banned member.
 * {@link #claim()} is what makes that race safe, and the target list is what lets the buttons be
 * removed from every copy once one is used.
 *
 * <p>Held by {@link net.nosial.spb.classes.sessions.ReportActionTracker}, keyed by the reported
 * message, so it is written by whatever submits a report and read by whatever handles the button
 * press. Instances are thread-safe.
 */
public final class ReportActionState
{
    private final List<NotificationTarget> targets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    /**
     * Records further delivered copies of the notification. Copies can still be added after the
     * action was claimed, since a moderator may press a button before every copy is delivered.
     *
     * @param delivered the delivered copies carrying buttons
     */
    public void addTargets(List<NotificationTarget> delivered)
    {
        this.targets.addAll(delivered);
    }

    /**
     * Returns every delivered copy of the notification recorded so far.
     *
     * @return the notification targets
     */
    public List<NotificationTarget> targets()
    {
        return List.copyOf(this.targets);
    }

    /**
     * Claims the action for the caller, exactly once.
     *
     * @return {@code true} for the first caller, {@code false} for every later one
     */
    public boolean claim()
    {
        return this.consumed.compareAndSet(false, true);
    }

    /**
     * Returns whether the action has already been taken.
     *
     * @return {@code true} once a moderator has acted
     */
    public boolean isConsumed()
    {
        return this.consumed.get();
    }
}
