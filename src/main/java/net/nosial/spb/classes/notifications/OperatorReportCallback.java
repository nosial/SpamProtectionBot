package net.nosial.spb.classes.notifications;

/**
 * Callback data layout for the action buttons on {@code #REPORT_ASSIGNED} notifications.
 *
 * <p>The buttons carry the report UUID and the requested action directly, as
 * {@code opr:<report_uuid>:<action>}, so they stay usable for as long as the report is open and
 * survive bot restarts. The longest value ({@code opr:} + 36-character UUID + {@code :SUSPICIOUS})
 * is 51 bytes, inside Telegram's 64-byte callback data limit.
 */
public final class OperatorReportCallback
{
    /** Namespace of the current, stateless notification buttons. */
    public static final String PREFIX = "opr";

    /**
     * Namespace of the buttons sent before they became stateless, which referenced an in-memory
     * session that no longer exists. They are still claimed, so pressing one gets an answer.
     */
    public static final String LEGACY_PREFIX = "operator-report";

    /** Action that closes the report without a classification. */
    public static final String CLOSE = "close";

    private OperatorReportCallback()
    {
    }

    /**
     * Builds the callback data for one notification button.
     *
     * @param reportUuid the report the button acts on
     * @param action {@link #CLOSE} or a {@code ClassificationFlag} name
     * @return the callback data
     */
    public static String data(String reportUuid, String action)
    {
        return PREFIX + ":" + reportUuid + ":" + action;
    }
}
