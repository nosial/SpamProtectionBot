package net.nosial.spb.objects.context;

import net.nosial.jfederation.enums.IncidentType;
import net.nosial.spb.classes.interfaces.TouchableSession;
import net.nosial.spb.enums.ReportPage;
import net.nosial.spb.objects.ReportAttachment;
import java.util.List;
import java.util.Map;

/**
 * A short-lived, user-bound session for the two-step {@code /report} dialog.
 *
 * <p>The session is created when a user invokes {@code /report} as a reply to a message without
 * providing an incident type or comment, or when a user forwards a message to the bot privately.
 * It tracks the target message, the reporter, the selected incident type, and the prompt message
 * used for the dialog so that replies to the prompt can be recognised and submitted as the report
 * comment.
 *
 * @param hash the opaque session identifier
 * @param reporterId the Telegram user id of the member submitting the report
 * @param chatId the Telegram chat id where the report originated
 * @param targetMessageId the Telegram message id being reported
 * @param targetAuthorId the Telegram user id of the author of the reported message
 * @param targetText the text content of the reported message, or {@code null}
 * @param attachments downloadable files attached to the reported message
 * @param targetMetadata every property of the reported message, flattened as Federation metadata
 *                       (see {@link net.nosial.spb.utilities.FlatMetadata}); empty when unknown
 * @param promptMessageId the message id of the dialog prompt, or {@code null}
 * @param ephemeral whether the dialog prompt is an ephemeral message visible only to the reporter
 * @param messageThreadId the forum topic id of the original report command, or {@code null}
 * @param page the current page of the two-step dialog
 * @param incidentType the selected incident type, or {@code null} until selected
 * @param reporterIsAdmin whether the reporter was a cached chat administrator when the session was created
 * @param createdAt epoch milliseconds when the session was created
 * @param lastUsedAt epoch milliseconds when the session was last touched
 */
public record ReportContext(
        String hash,
        long reporterId,
        long chatId,
        long targetMessageId,
        long targetAuthorId,
        String targetText,
        List<ReportAttachment> attachments,
        Map<String, Object> targetMetadata,
        Integer promptMessageId,
        boolean ephemeral,
        Integer messageThreadId,
        ReportPage page,
        IncidentType incidentType,
        boolean reporterIsAdmin,
        long createdAt,
        long lastUsedAt)
        implements TouchableSession
{
    public ReportContext
    {
        targetMetadata = targetMetadata == null ? Map.of() : targetMetadata;
    }

    /**
     * Returns a copy of this session with {@link #lastUsedAt} refreshed to now.
     *
     * @return the touched session
     */
    public ReportContext touch()
    {
        return new ReportContext(this.hash, this.reporterId, this.chatId, this.targetMessageId,
                this.targetAuthorId, this.targetText, this.attachments, this.targetMetadata, this.promptMessageId, this.ephemeral, this.messageThreadId,
                this.page, this.incidentType, this.reporterIsAdmin, this.createdAt, System.currentTimeMillis());
    }

    /**
     * Returns a copy of this session with the given dialog page.
     *
     * @param page the new page
     * @return the updated session
     */
    public ReportContext withPage(ReportPage page)
    {
        return new ReportContext(this.hash, this.reporterId, this.chatId, this.targetMessageId,
                this.targetAuthorId, this.targetText, this.attachments, this.targetMetadata, this.promptMessageId, this.ephemeral, this.messageThreadId,
                page, this.incidentType, this.reporterIsAdmin, this.createdAt, System.currentTimeMillis());
    }

    /**
     * Returns a copy of this session with the given incident type selected.
     *
     * @param incidentType the selected incident type
     * @return the updated session
     */
    public ReportContext withIncidentType(IncidentType incidentType)
    {
        return new ReportContext(this.hash, this.reporterId, this.chatId, this.targetMessageId,
                this.targetAuthorId, this.targetText, this.attachments, this.targetMetadata, this.promptMessageId, this.ephemeral, this.messageThreadId,
                this.page, incidentType, this.reporterIsAdmin, this.createdAt, System.currentTimeMillis());
    }

    /**
     * Returns a copy of this session with the given prompt message id.
     *
     * @param promptMessageId the message id, or {@code null}
     * @return the updated session
     */
    public ReportContext withPromptMessageId(Integer promptMessageId)
    {
        return new ReportContext(this.hash, this.reporterId, this.chatId, this.targetMessageId,
                this.targetAuthorId, this.targetText, this.attachments, this.targetMetadata, promptMessageId, this.ephemeral, this.messageThreadId,
                this.page, this.incidentType, this.reporterIsAdmin, this.createdAt, System.currentTimeMillis());
    }

    /**
     * Returns a copy of this session with the given ephemeral flag.
     *
     * @param ephemeral whether the prompt is ephemeral
     * @return the updated session
     */
    public ReportContext withEphemeral(boolean ephemeral)
    {
        return new ReportContext(this.hash, this.reporterId, this.chatId, this.targetMessageId,
                this.targetAuthorId, this.targetText, this.attachments, this.targetMetadata, this.promptMessageId, ephemeral, this.messageThreadId,
                this.page, this.incidentType, this.reporterIsAdmin, this.createdAt, System.currentTimeMillis());
    }
}
