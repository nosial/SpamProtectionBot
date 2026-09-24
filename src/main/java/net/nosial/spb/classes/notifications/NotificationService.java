package net.nosial.spb.classes.notifications;

import net.nosial.spb.classes.interfaces.ReportSource;
import net.nosial.spb.classes.interfaces.NotificationSink;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.managers.ManagerRegistry;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.classes.sessions.OperatorReportSessionManager;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.objects.context.OperatorReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls each authenticated operator's assigned open reports and sends a Telegram notification for
 * reports first observed after that operator's baseline poll.
 *
 * <p>All report tracking is process-local. The first successful poll for an operator records the
 * reports currently assigned to that operator without sending notifications. Every later successful
 * poll notifies only for report UUIDs absent from that in-memory baseline. Adding, removing, or
 * changing an operator credential resets that operator's baseline, which prevents an operator from
 * being flooded with pre-existing reports after authentication changes.
 *
 * <p>The service owns one non-daemon thread. It remains idle while the {@code operators} table is
 * empty so operators authenticated after startup are picked up on the next interval. Federation
 * requests use a short-lived client authenticated with the individual operator's stored token;
 * the process-wide Federation client is never mutated.
 */
public final class NotificationService implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
    private static final int REPORT_PAGE_SIZE = 100;

    /** How long the shutdown waits for the polling thread to finish its current pass. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000;

    private final ManagerRegistry managers;
    private final long intervalMillis;
    private final ReportSource reportSource;
    private final NotificationSink notificationSink;
    private final OperatorReportSessionManager operatorReportSessions;
    private final Map<Long, OperatorState> operatorStates = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread worker;

    /**
     * Creates a notification service backed by the Telegram client and Federation endpoint.
     *
     * @param managers source of authenticated operator identities and language preferences
     * @param telegramClient client used to deliver private Telegram messages
     * @param federation the Federation server the reports are polled from
     * @param interval time between completed poll cycles
     * @param operatorReportSessions memory-bound action sessions for notification buttons
     */
    public NotificationService(ManagerRegistry managers, OkHttpTelegramClient telegramClient,
                               FederationService federation, Duration interval,
                               OperatorReportSessionManager operatorReportSessions)
    {
        this(managers, interval, identity -> loadOpenedReports(federation, identity),
                new ReportNotificationSender(telegramClient, federation, managers), operatorReportSessions);
        Objects.requireNonNull(telegramClient, "telegramClient must not be null");
        Objects.requireNonNull(federation, "federation must not be null");
    }

    

    /**
     * Assembles the poller over its collaborators.
     *
     * @param managers the operator credential store
     * @param interval how long to wait between completed passes
     * @param reportSource reads the open reports assigned to an operator
     * @param notificationSink delivers one report to one operator
     * @param operatorReportSessions the one-shot actions attached to each notification
     */
    private NotificationService(ManagerRegistry managers, Duration interval, ReportSource reportSource,
                                NotificationSink notificationSink,
                                OperatorReportSessionManager operatorReportSessions)
    {
        this.managers = Objects.requireNonNull(managers, "managers must not be null");
        this.reportSource = Objects.requireNonNull(reportSource, "reportSource must not be null");
        this.notificationSink = Objects.requireNonNull(notificationSink, "notificationSink must not be null");
        this.operatorReportSessions = Objects.requireNonNull(operatorReportSessions, "operatorReportSessions must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative())
        {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.intervalMillis = interval.toMillis();
        if (this.intervalMillis < 1)
        {
            throw new IllegalArgumentException("interval must be at least one millisecond");
        }

        this.worker = new Thread(this::run, "spb-notifications");
        this.worker.setDaemon(false);
    }

    /**
     * Starts the dedicated notification thread. Calling this method more than once has no effect.
     */
    public void start()
    {
        if (this.closed.get())
        {
            throw new IllegalStateException("Notification service is closed");
        }
        if (this.started.compareAndSet(false, true))
        {
            this.worker.start();
        }
    }

    /**
     * Executes a single poll cycle.
     *
     * <p>This is package-visible so the first-poll baseline and duplicate suppression behavior can
     * be tested without waiting for the service interval. Production calls it only from the service
     * thread.
     */
    void poll()
    {
        Map<Long, OperatorIdentity> operators;
        try
        {
            operators = this.managers.operators().listOperators();
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Unable to load authenticated operators for notification polling: {}", e.getMessage());
            return;
        }

        this.operatorStates.keySet().retainAll(operators.keySet());
        for (Map.Entry<Long, OperatorIdentity> entry : operators.entrySet())
        {
            pollOperator(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Polls the reports assigned to a specific operator and sends notifications for any new reports
     * that have not been previously seen by the operator.
     *
     * @param telegramUserId the Telegram user ID of the operator to be notified
     * @param identity the authenticated identity of the operator whose reports are being polled
     */
    private void pollOperator(long telegramUserId, OperatorIdentity identity)
    {
        List<ReportRecord> reports;
        try
        {
            reports = this.reportSource.openedReports(identity);
        }
        catch (Exception e)
        {
            LOGGER.warn("Unable to load opened reports for operator {}: {}", identity.operatorUuid(), e.getMessage());
            return;
        }

        OperatorState state = this.operatorStates.get(telegramUserId);
        if (state == null || !state.identity().equals(identity))
        {
            this.operatorStates.put(telegramUserId, new OperatorState(identity, reportIds(reports)));
            LOGGER.debug("Established notification baseline for Telegram user {} with {} open reports",
                    telegramUserId, reports.size());
            return;
        }

        for (ReportRecord report : reports)
        {
            if (report == null || report.uuid() == null || report.uuid().isBlank() || !state.seenReportIds().add(report.uuid()))
            {
                continue;
            }

            OperatorReportContext session = this.operatorReportSessions.create(telegramUserId, identity, report.uuid());
            try
            {
                this.notificationSink.send(telegramUserId, report, session);
            }
            catch (Exception e)
            {
                LOGGER.warn("Unable to send notification for report {} to Telegram user {}: {}",
                        report.uuid(), telegramUserId, e.getMessage());
            }
        }
    }

    /**
     * Executes the notification service's main loop, running continuously until the service is
     * shut down. The method performs repeated polling cycles to deliver notifications at regular
     * intervals, and handles thread interruptions gracefully.
     * <p>
     * The method adheres to the following behavior:
     * - Continuously performs polling by invoking the {@code poll()} method while the service is
     *   not closed.
     * - If the service's shutdown signal is detected during execution, the method exits immediately.
     * - Introduces delays between successive polling cycles using {@link Thread#sleep(long)} to
     *   respect the configured polling interval.
     * - Handles {@link InterruptedException} during the sleep state, logging a warning if the
     *   interruption occurs outside a shutdown context, and resumes execution.
     * <p>
     * The method operates in the context of a dedicated thread and is not intended to be invoked
     * directly from external callers.
     */
    private void run()
    {
        while (!this.closed.get())
        {
            poll();
            if (this.closed.get())
            {
                return;
            }

            try
            {
                Thread.sleep(this.intervalMillis);
            }
            catch (InterruptedException e)
            {
                if (this.closed.get())
                {
                    return;
                }
                LOGGER.warn("Notification service interrupted outside shutdown; continuing");
            }
        }
    }

    /**
     * Stops the service and waits up to ten seconds for its thread to finish.
     */
    @Override
    public void close()
    {
        if (!this.closed.compareAndSet(false, true))
        {
            return;
        }
        this.worker.interrupt();
        if (!this.started.get() || Thread.currentThread() == this.worker)
        {
            return;
        }

        try
        {
            this.worker.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (this.worker.isAlive())
            {
                LOGGER.warn("Notification service did not stop within 10 seconds");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for notification service shutdown");
        }
    }

    /**
     * Reads the reports currently open for one operator.
     *
     * @param federation the Federation server
     * @param identity the operator whose reports to read
     * @return the open reports, oldest first
     * @throws FederationException If the server is unavailable or rejected the call
     */
    private static List<ReportRecord> loadOpenedReports(FederationService federation, OperatorIdentity identity)
            throws FederationException
    {
        Objects.requireNonNull(federation, "federation must not be null");
        return federation.openAssignedReports(identity.accessToken(), REPORT_PAGE_SIZE);
    }

    /**
     * Extracts and returns a set of unique UUIDs from the provided list of report records.
     * Each UUID is obtained from the {@code uuid()} method of non-null {@code ReportRecord} objects
     * in the input list, provided that the UUID is not blank.
     *
     * @param reports the list of {@code ReportRecord} objects to process; can include null values.
     * @return a {@code Set} of unique non-blank UUID strings extracted from the reports.
     */
    private static Set<String> reportIds(List<ReportRecord> reports)
    {
        Set<String> reportIds = new HashSet<>();
        for (ReportRecord report : reports)
        {
            if (report != null && report.uuid() != null && !report.uuid().isBlank())
            {
                reportIds.add(report.uuid());
            }
        }
        return reportIds;
    }

    /**
     * Represents the state of an operator within the notification service.
     *
     * <p>This record holds the authenticated identity of the operator along with a set of
     * unique report IDs that have already been processed or seen by the operator. The state
     * facilitates duplicate suppression during the notification process by tracking reports
     * that have been handled for a specific operator.
     *
     * <p>The {@code identity} field contains the immutable credentials associated with the
     * operator, as provided by the Federation server. The {@code seenReportIds} field stores
     * a collection of unique report identifiers that have been notified to the operator,
     * allowing the service to differentiate between new and previously seen reports.
     */
    private record OperatorState(OperatorIdentity identity, Set<String> seenReportIds)
    {
    }
}
