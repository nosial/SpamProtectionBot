package net.nosial.spb.classes.sessions;

import org.telegram.telegrambots.meta.api.objects.message.Message;
import net.nosial.spb.objects.context.FalsePositiveReportContext;
import net.nosial.spb.objects.ReportAttachment;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Stores one-time scanning false-positive actions for one hour.
 *
 * <p>A single session is attached to every delivery of the corresponding notification. The first
 * moderator to claim it wins atomically; all later clicks, including clicks in other notification
 * chats, are rejected without submitting a duplicate report.
 */
public final class FalsePositiveReportSessionManager extends AbstractSessionManager<FalsePositiveReportContext>
{
    public static final String CALLBACK_PREFIX = "false-report";
    private static final long SESSION_EXPIRY_HOURS = 1;

    /**
     * Constructs a {@code FalsePositiveReportSessionManager} instance with a default session
     * expiration duration of one hour.
     * <p>
     * This manager is responsible for handling sessions associated with one-time false-positive
     * reporting actions triggered by a scanning process. Each session is tied to a specific notification,
     * allowing atomic claiming by a single moderator. Sessions are automatically evicted after
     * the predefined expiration time to ensure timely cleanup of stale data.
     */
    public FalsePositiveReportSessionManager()
    {
        super(SESSION_EXPIRY_HOURS, TimeUnit.HOURS);
    }

    /**
     * Opens a one-shot false-positive action for a message a scan acted on.
     *
     * @param message the message the scan acted on
     * @param text the content that was scanned
     * @param attachments the files that were attached to it
     * @return the stored session
     */
    public FalsePositiveReportContext create(Message message, String text, List<ReportAttachment> attachments)
    {
        return store(new FalsePositiveReportContext(generateHash(), message, text, attachments));
    }

    /**
     * Retrieves a session associated with the given hash from the cache, allowing atomic claiming.
     * If the session cannot be claimed or does not exist, it returns {@code null}.
     * Once claimed, the session is evicted from the cache.
     *
     * @param hash the unique identifier for the session to retrieve; must not be {@code null} or blank
     * @return the claimed {@code FalsePositiveReportContext} session, or {@code null} if no session
     *         exists for the provided hash or if the session could not be claimed
     */
    public FalsePositiveReportContext take(String hash)
    {
        if (hash == null || hash.isBlank())
        {
            return null;
        }
        FalsePositiveReportContext session = cache().getIfPresent(hash);
        if (session == null || !session.claim())
        {
            return null;
        }
        evict(session);
        return session;
    }

    /**
     * Generates the callback data string for the specified false-positive report session.
     *
     * @param session the {@code FalsePositiveReportContext} representing the session associated
     *                with the false-positive report; must not be {@code null}
     * @return a uniquely identifiable callback string for the session in the format
     *         "false-report:<hash>"
     * @throws NullPointerException if {@code session} is {@code null}
     */
    public static String callbackData(FalsePositiveReportContext session)
    {
        Objects.requireNonNull(session, "session must not be null");
        return CALLBACK_PREFIX + ":" + session.hash();
    }
}
