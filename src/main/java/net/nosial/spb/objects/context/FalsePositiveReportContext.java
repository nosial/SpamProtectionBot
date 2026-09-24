package net.nosial.spb.objects.context;

import org.telegram.telegrambots.meta.api.objects.message.Message;
import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.classes.interfaces.Session;
import net.nosial.spb.objects.ReportAttachment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time evidence snapshot for a moderator to report a scanning false positive.
 *
 * <p>The snapshot is shared by every copy of one scanning notification. Its atomic claim prevents
 * two moderators from submitting duplicate false-positive reports through separate destinations.
 */
public final class FalsePositiveReportContext implements Session
{
    private final String hash;
    private final long chatId;
    private final int messageId;
    private final long authorId;
    private final String text;
    private final List<ReportAttachment> attachments;
    private final Map<String, Object> metadata;
    private final long createdAt;
    private final AtomicBoolean available = new AtomicBoolean(true);

    /**
     * Creates the one-shot action offered on a scanning notification, timestamped now.
     *
     * <p>The message being disputed says what the action is about, so it is read rather than
     * unpacked by the caller.
     *
     * @param hash the opaque handle the button carries
     * @param message the message the scan acted on
     * @param text the content that was scanned
     * @param attachments the files that were attached to it
     */
    public FalsePositiveReportContext(String hash, Message message, String text, List<ReportAttachment> attachments)
    {
        this(hash, message.getChatId(), message.getMessageId(),
                message.getFrom() != null ? message.getFrom().getId() : 0L, text, attachments,
                FlatMetadata.of(message), System.currentTimeMillis());
    }

    public FalsePositiveReportContext(String hash, long chatId, int messageId, long authorId, String text,
                                      List<ReportAttachment> attachments, Map<String, Object> metadata, long createdAt)
    {
        this.hash = Objects.requireNonNull(hash, "hash must not be null");
        this.chatId = chatId;
        this.messageId = messageId;
        this.authorId = authorId;
        this.text = text;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        this.metadata = metadata == null ? Map.of() : metadata;
        this.createdAt = createdAt;
    }


    /**
     * Retrieves the unique identifier of the chat associated with this context.
     *
     * @return the chat ID as a {@code long}.
     */
    public long chatId()
    {
        return this.chatId;
    }

    /**
     * Retrieves the unique identifier of the message associated with this context.
     *
     * @return the message ID as an {@code int}.
     */
    public int messageId()
    {
        return this.messageId;
    }

    /**
     * Retrieves the unique identifier of the author associated with this context.
     *
     * @return the author ID as a {@code long}.
     */
    public long authorId()
    {
        return this.authorId;
    }

    public String text()
    {
        return this.text;
    }

    /**
     * Retrieves the metadata associated with this context.
     *
     * @return a {@code Map<String, Object>} containing additional metadata
     *         related to this context
     */
    public Map<String, Object> metadata()
    {
        return this.metadata;
    }

    /**
     * Returns the list of report attachments associated with this context.
     *
     * @return a list of {@code ReportAttachment} objects representing the files attached to the message being reported
     */
    public List<ReportAttachment> attachments()
    {
        return this.attachments;
    }

    /**
     * Returns the timestamp, in epoch milliseconds, representing when this context was created.
     *
     * @return the creation timestamp of this context in epoch milliseconds
     */
    public long createdAt()
    {
        return this.createdAt;
    }

    /**
     * Attempts to claim this context for a one-time action by marking it as unavailable.
     *
     * <p>This method utilizes an atomic operation to ensure only one thread or process can
     * successfully claim the context. Once claimed, subsequent attempts to claim it will
     * fail until it is reset.
     *
     * @return {@code true} if the context was successfully claimed, or {@code false} if it
     *         was already claimed by another process or thread.
     */
    public boolean claim()
    {
        return this.available.compareAndSet(true, false);
    }

    @Override
    public String hash()
    {
        return this.hash;
    }
}
