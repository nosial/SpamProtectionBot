package net.nosial.spb.enums;

/**
 * Who asked for a report, which is all that distinguishes one submission from another.
 *
 * <p>Several features end at the same Federation call: a member or administrator reporting a
 * message, a moderator saying a scan was wrong, and an operator creating the report that backs a
 * blacklist. They differ only in how the evidence is tagged and who hears about it afterwards, and
 * both of those follow from which one it is.
 */
public enum ReportOrigin
{
    /** A chat member filed it; they get a summary and the moderators get a notification. */
    MEMBER("user_report", true, true),

    /**
     * An administrator filed it with {@code /report}; they get a summary same as a member, but
     * moderators are not separately notified since the reporter already is one.
     */
    ADMIN("admin_report", true, false),

    /** A moderator disputed a scan; filed quietly, tagged so the server can tell it apart. */
    FALSE_POSITIVE("false_report", false, false),

    /** An operator needs a report to blacklist against; created as a reference, told to nobody. */
    OPERATOR_REFERENCE("admin_report", false, false);

    /** Evidence tag marking a report that says a previous scan was wrong. */
    public static final String FALSE_REPORT_EVIDENCE_TAG = "false_report";

    private final String evidenceTag;
    private final boolean summarised;
    private final boolean notifiesModerators;

    /**
     * Creates an origin.
     *
     * @param evidenceTag the tag attached to the report's evidence
     * @param summarised whether the reporter gets a summary back
     * @param notifiesModerators whether the chat's moderators are told
     */
    ReportOrigin(String evidenceTag, boolean summarised, boolean notifiesModerators)
    {
        this.evidenceTag = evidenceTag;
        this.summarised = summarised;
        this.notifiesModerators = notifiesModerators;
    }

    /**
     * Returns the tag attached to the report's evidence.
     *
     * @return the evidence tag
     */
    public String evidenceTag()
    {
        return this.evidenceTag;
    }

    /**
     * Returns whether a summary goes back to whoever filed the report.
     *
     * @return {@code true} when the reporter is answered
     */
    public boolean isSummarised()
    {
        return this.summarised;
    }

    /**
     * Returns whether the chat's moderators are notified of the report.
     *
     * @return {@code true} when moderators are told
     */
    public boolean notifiesModerators()
    {
        return this.notifiesModerators;
    }
}
