package net.nosial.spb.objects;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accumulates the messages and attachments of one media group for automated moderation.
 *
 * <p>Instances are stored in the runtime cache keyed by chat and media group id. The atomic
 * restriction flag prevents an album being deleted or restricted more than once.
 */
public final class MediaGroupState
{
    private final long authorId;
    private final Set<Integer> messageIds = ConcurrentHashMap.newKeySet();
    private final Map<String, ReportAttachment> attachments = new ConcurrentHashMap<>();
    private final AtomicBoolean restricted = new AtomicBoolean(false);

    public MediaGroupState(long authorId)
    {
        this.authorId = authorId;
    }

    public long authorId()
    {
        return this.authorId;
    }

    public void addMessageId(int messageId)
    {
        this.messageIds.add(messageId);
    }

    public Set<Integer> messageIds()
    {
        return this.messageIds;
    }

    public void addAttachment(ReportAttachment attachment)
    {
        this.attachments.putIfAbsent(attachment.fileId(), attachment);
    }

    public Map<String, ReportAttachment> attachments()
    {
        return this.attachments;
    }

    public boolean isRestricted()
    {
        return this.restricted.get();
    }

    /**
     * Claims the media group for a one-time automated moderation action.
     *
     * @return {@code true} when this call performed the claim
     */
    public boolean restrict()
    {
        return this.restricted.compareAndSet(false, true);
    }
}