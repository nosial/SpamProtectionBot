package net.nosial.spb.classes.sessions;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.objects.context.OperatorReportContext;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores one-time report-notification actions in process memory.
 *
 * <p>Sessions expire eight hours after notification delivery, regardless of reads, so an operator
 * cannot keep an unattended report action alive indefinitely. A successful or attempted owned
 * action atomically consumes its session before the Federation mutation, preventing repeated close
 * requests from duplicate Telegram callback deliveries.
 */
public final class OperatorReportSessionManager extends AbstractSessionManager<OperatorReportContext>
{
    private static final long SESSION_EXPIRY_HOURS = 8;
    public static final String CALLBACK_PREFIX = "operator-report";
    private final Cache<String, AtomicBoolean> consumed;

    public OperatorReportSessionManager()
    {
        super(SESSION_EXPIRY_HOURS, TimeUnit.HOURS);
        this.consumed = Cache.create(10_000, SESSION_EXPIRY_HOURS, TimeUnit.HOURS);
    }

    /**
     * Creates a report action session for the operator that received the notification.
     *
     * @param operatorTelegramUserId Telegram user id permitted to use the action buttons
     * @param operatorIdentity authenticated Federation identity snapshot
     * @param reportUuid report that the action will close
     * @return the newly created session
     */
    public OperatorReportContext create(long operatorTelegramUserId, OperatorIdentity operatorIdentity, String reportUuid)
    {
        Objects.requireNonNull(operatorIdentity, "operatorIdentity must not be null");
        if (reportUuid == null || reportUuid.isBlank())
        {
            throw new IllegalArgumentException("reportUuid must not be blank");
        }

        OperatorReportContext session = new OperatorReportContext(generateHash(), operatorTelegramUserId,
                operatorIdentity, reportUuid, System.currentTimeMillis());
        return store(session);
    }

    /**
     * Consumes an active session only when it belongs to the clicking Telegram user.
     *
     * @param hash callback-session identifier
     * @param operatorTelegramUserId expected owner
     * @return the consumed session, or {@code null} when absent, expired, or owned by another user
     */
    public OperatorReportContext takeOwned(String hash, long operatorTelegramUserId)
    {
        OperatorReportContext session = cache().getIfPresent(hash);
        if (session == null || session.operatorTelegramUserId() != operatorTelegramUserId)
        {
            return null;
        }
        AtomicBoolean claim = this.consumed.get(hash, ignored -> new AtomicBoolean(false));
        if (!claim.compareAndSet(false, true))
        {
            return null;
        }
        evict(session);
        return session;
    }
}
