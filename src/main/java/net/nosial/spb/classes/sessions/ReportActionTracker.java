package net.nosial.spb.classes.sessions;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.objects.NotificationTarget;
import net.nosial.spb.objects.ReportActionState;

import java.util.ArrayList;
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
     * Records the notifications delivered for a report of the given message. When the message was
     * already reported and not yet acted on, the new notifications are added to the earlier ones,
     * since an action on either removes the message they are both about.
     *
     * @param chatId the protected chat the reported message lives in
     * @param messageId the reported message
     * @param targets the notifications that carry moderation buttons
     */
    public void track(long chatId, long messageId, List<NotificationTarget> targets)
    {
        String key = key(chatId, messageId);
        ReportActionState existing = this.states.getIfPresent(key);
        List<NotificationTarget> merged = new ArrayList<>(targets);
        if (existing != null && !existing.isConsumed())
        {
            merged.addAll(existing.targets());
        }
        this.states.put(key, new ReportActionState(merged));
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
        ReportActionState state = this.states.get(key(chatId, messageId), ignored -> new ReportActionState(List.of()));
        return state.claim() ? state : null;
    }

    /**
     * Builds the cache key of a reported message.
     *
     * @param chatId the protected chat
     * @param messageId the reported message
     * @return the key
     */
    private static String key(long chatId, long messageId)
    {
        return chatId + ":" + messageId;
    }
}
