package net.nosial.spb.objects;

import net.nosial.spb.classes.sessions.ConfigurationSessionManager;
import net.nosial.spb.classes.sessions.FalsePositiveReportSessionManager;
import net.nosial.spb.classes.sessions.OperatorReportSessionManager;
import net.nosial.spb.classes.sessions.ReportSessionManager;

import java.util.Objects;

/**
 * The short-lived dialogs the bot keeps open while a user works through a multi-step flow.
 *
 * <p>Each kind of dialog has its own manager because each expires on its own schedule and is keyed
 * differently. Bundling them means a handler reaches the one it needs through the context without
 * the context growing a component per dialog.
 *
 * @param configuration configuration sessions opened by {@code /start} or {@code /settings} in a group
 * @param report report dialogs opened by {@code /report}
 * @param falsePositive one-shot actions attached to a moderation notification
 * @param operatorReport one-shot actions attached to an assigned-report notification
 */
public record SessionRegistry(
        ConfigurationSessionManager configuration,
        ReportSessionManager report,
        FalsePositiveReportSessionManager falsePositive,
        OperatorReportSessionManager operatorReport)
{
    /**
     * Rejects a partially-populated registry, since a missing manager would only fail later on a
     * worker thread.
     */
    public SessionRegistry
    {
        Objects.requireNonNull(configuration, "configuration session manager must not be null");
        Objects.requireNonNull(report, "report session manager must not be null");
        Objects.requireNonNull(falsePositive, "false-positive session manager must not be null");
        Objects.requireNonNull(operatorReport, "operator report session manager must not be null");
    }

    /**
     * Opens a fresh set of dialog managers, one of each kind.
     */
    public SessionRegistry()
    {
        this(new ConfigurationSessionManager(), new ReportSessionManager(),
                new FalsePositiveReportSessionManager(), new OperatorReportSessionManager());
    }
}
