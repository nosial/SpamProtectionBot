package net.nosial.spb.classes.sessions;

import java.util.List;
import java.util.Map;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.utilities.MessageContent;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.spb.classes.Cache;
import net.nosial.spb.enums.ReportPage;
import net.nosial.spb.objects.context.ReportContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory store for report dialog sessions created from group {@code /report} commands and from
 * forwarded messages sent to the bot privately.
 *
 * <p>Sessions are keyed by an opaque hash and expire after a period of inactivity. Every lookup
 * refreshes the entry's expiry. The session is bound to the original reporter and target message,
 * so callback queries and replies cannot be hijacked by other members.
 *
 * <p>The manager also maintains a reverse index from the prompt message id to the session hash, so
 * a reply to the prompt can locate the dialog and submit the comment. The atomic
 * {@link #takeOwned(String, long)} and {@link #takeOwnedByPromptMessageId(long, int, long)} methods
 * validate that the caller owns the session before removing it in a single step, so an invalid
 * callback can never destroy a live session while rapid Submit clicks or replies still cannot
 * submit the same report twice.
 */
public final class ReportSessionManager extends AbstractSessionManager<ReportContext>
{
    private static final long SESSION_EXPIRY_MINUTES = 5;
    private final ReverseIndex<String> promptIndex;
    private final Cache<String, AtomicBoolean> consumed;

    public ReportSessionManager()
    {
        super(SESSION_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.promptIndex = new ReverseIndex<>(SESSION_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.consumed = Cache.create(10_000, SESSION_EXPIRY_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Opens a dialog to report a message that was replied to in a chat.
     *
     * @param command the {@code /report} message
     * @param target the message being reported
     * @param attachments the files attached to the reported message
     * @param reporterIsAdmin whether the reporter is an administrator of the chat
     * @param ephemeral whether the prompt is shown only to the reporter
     * @return the created session
     */
    public ReportContext create(Message command, Message target, List<ReportAttachment> attachments,
                                boolean reporterIsAdmin, boolean ephemeral)
    {
        return create(command.getFrom().getId(), command.getChatId(), target.getMessageId(),
                target.getFrom() != null ? target.getFrom().getId() : 0L, MessageContent.textOrCaption(target),
                attachments, FlatMetadata.of(target), target.getMessageThreadId(), reporterIsAdmin, ephemeral);
    }

    /**
     * Opens a dialog to report a message forwarded into the bot's private chat.
     *
     * <p>A forward carries its own author and original message id rather than the ones on the
     * message as it arrived, so those are supplied instead of being read off it.
     *
     * @param forward the forwarded message, as it arrived in the private chat
     * @param originalMessageId the message id the forward came from
     * @param originalAuthorId the Telegram user id of whoever is being reported
     * @param attachments the files attached to the forwarded message
     * @param reporterIsAdmin whether the reporter is an administrator of the chat
     * @return the created session
     */
    public ReportContext create(Message forward, long originalMessageId, long originalAuthorId,
                                List<ReportAttachment> attachments, boolean reporterIsAdmin)
    {
        return create(forward.getFrom().getId(), forward.getChatId(), originalMessageId, originalAuthorId,
                MessageContent.textOrCaption(forward), attachments, FlatMetadata.of(forward), null,
                reporterIsAdmin, false);
    }

    /**
     * Stores a new dialog at its first page.
     *
     * @param reporterId who is reporting
     * @param chatId where the report was opened
     * @param targetMessageId the message being reported
     * @param targetAuthorId who is being reported
     * @param targetText the reported content
     * @param attachments the files attached to it
     * @param targetMetadata every property of the reported message, flattened
     * @param messageThreadId the forum topic the report belongs to, or {@code null}
     * @param reporterIsAdmin whether the reporter is an administrator
     * @param ephemeral whether the prompt is shown only to the reporter
     * @return the created session
     */
    private ReportContext create(long reporterId, long chatId, long targetMessageId, long targetAuthorId,
                                 String targetText, List<ReportAttachment> attachments,
                                 Map<String, Object> targetMetadata, Integer messageThreadId,
                                 boolean reporterIsAdmin, boolean ephemeral)
    {
        long now = System.currentTimeMillis();
        return store(new ReportContext(generateHash(), reporterId, chatId, targetMessageId, targetAuthorId,
                targetText, attachments, targetMetadata, null, ephemeral, messageThreadId, ReportPage.INCIDENT, null,
                reporterIsAdmin, now, now));
    }

    /**
     * Atomically removes and returns the session with the given hash when it belongs to the given
     * reporter, or {@code null} when the session is absent, expired, or owned by a different user.
     *
     * @param hash the session hash
     * @param reporterId the expected owner of the session
     * @return the removed session, or {@code null}
     */
    public ReportContext takeOwned(String hash, long reporterId)
    {
        ReportContext session = cache().getIfPresent(hash);
        if (session == null || session.reporterId() != reporterId)
        {
            return null;
        }
        AtomicBoolean claim = this.consumed.get(hash, ignored -> new AtomicBoolean(false));
        if (!claim.compareAndSet(false, true))
        {
            return null;
        }
        this.promptIndex.clear(promptKey(session));
        evict(session);
        return session;
    }

    /**
     * Looks up an active session by the prompt message id and refreshes its expiry.
     *
     * @param chatId the Telegram chat id
     * @param promptMessageId the message id of the dialog prompt
     * @return the session, or {@code null} when absent or expired
     */
    public ReportContext findByPromptMessageId(long chatId, int promptMessageId)
    {
        String hash = this.promptIndex.resolve(promptKey(chatId, promptMessageId));
        if (hash == null)
        {
            return null;
        }
        return find(hash);
    }

    /**
     * Atomically removes and returns the session associated with the given prompt message id when
     * it belongs to the given reporter, or {@code null} when absent, expired, or owned by a
     * different user.
     *
     * @param chatId the Telegram chat id
     * @param promptMessageId the message id of the dialog prompt
     * @param reporterId the expected owner of the session
     * @return the removed session, or {@code null}
     */
    public ReportContext takeOwnedByPromptMessageId(long chatId, int promptMessageId, long reporterId)
    {
        String hash = this.promptIndex.resolve(promptKey(chatId, promptMessageId));
        if (hash == null)
        {
            return null;
        }
        return takeOwned(hash, reporterId);
    }

    /**
     * Stores the selected incident type and advances the dialog to the comment page.
     *
     * @param hash the session hash
     * @param incidentType the selected incident type
     * @return the updated session, or {@code null} when the session no longer exists
     */
    public ReportContext updateIncidentType(String hash, IncidentType incidentType)
    {
        ReportContext session = find(hash);
        if (session == null)
        {
            return null;
        }
        session = session.withIncidentType(incidentType).withPage(ReportPage.COMMENT);
        cache().put(hash, session);
        return session;
    }

    /**
     * Stores the prompt message id and indexes it for reply detection.
     *
     * @param hash the session hash
     * @param promptMessageId the message id, or {@code null} to clear
     */
    public void updatePromptMessageId(String hash, Integer promptMessageId)
    {
        ReportContext session = find(hash);
        if (session == null)
        {
            return;
        }
        this.promptIndex.clear(promptKey(session));
        session = session.withPromptMessageId(promptMessageId);
        cache().put(hash, session);
        if (promptMessageId != null)
        {
            this.promptIndex.store(promptKey(session.chatId(), promptMessageId), hash);
        }
    }

    @Override
    public void invalidate(String hash)
    {
        ReportContext session = cache().getIfPresent(hash);
        if (session != null)
        {
            this.promptIndex.clear(promptKey(session));
        }
        super.invalidate(hash);
    }

    /**
     * Generates a key for identifying a prompt message in a reporting session.
     *
     * @param session the reporting session containing the context of the prompt
     *                message, including the chat ID and prompt message ID
     * @return a unique key based on the chat ID and prompt message ID, or {@code null}
     *         if the session does not have a prompt message ID
     */
    private static String promptKey(ReportContext session)
    {
        return session.promptMessageId() != null
                ? promptKey(session.chatId(), session.promptMessageId())
                : null;
    }

    /**
     * Generates a unique key for identifying a prompt message in a reporting session.
     *
     * @param chatId the Telegram chat id where the prompt message is located
     * @param promptMessageId the unique message id of the dialog prompt within the chat
     * @return a concatenated key consisting of the chat ID and prompt message ID, separated by a colon
     */
    private static String promptKey(long chatId, int promptMessageId)
    {
        return chatId + ":" + promptMessageId;
    }
}
