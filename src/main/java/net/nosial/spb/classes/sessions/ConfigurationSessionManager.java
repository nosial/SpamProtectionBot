package net.nosial.spb.classes.sessions;

import net.nosial.spb.enums.ConfigurationPage;
import net.nosial.spb.objects.context.ConfigurationContext;

import java.util.concurrent.TimeUnit;

/**
 * In-memory store for configuration sessions created from group {@code /start} commands.
 *
 * <p>Sessions are keyed by an opaque hash and expire after a period of inactivity. Every lookup
 * refreshes the entry's expiry. Only the original caller and target chat are recorded in the
 * session, so callback queries can be validated against hijacking.
 *
 * <p>The manager also maintains a reverse index from the active channel verification code to the
 * session hash, so a {@code /connect <code>} command in a channel can locate the private
 * configuration menu to update.
 */
public final class ConfigurationSessionManager extends AbstractSessionManager<ConfigurationContext>
{
    private static final long SESSION_EXPIRY_MINUTES = 5;
    private final ReverseIndex<Long> verificationIndex;

    public ConfigurationSessionManager()
    {
        super(SESSION_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.verificationIndex = new ReverseIndex<>(SESSION_EXPIRY_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Creates a new configuration session for the given caller and target chat.
     *
     * @param userId the administrator's Telegram user id
     * @param chatId the Telegram chat id being configured
     * @return the created session
     */
    public ConfigurationContext create(long userId, long chatId)
    {
        String hash = generateHash();
        long now = System.currentTimeMillis();
        ConfigurationContext session = new ConfigurationContext(hash, userId, chatId, now, now,
                ConfigurationPage.MAIN, null, null, false, null, false);
        return store(session);
    }

    /**
     * Creates a new ephemeral configuration session for the given caller and target chat.
     *
     * <p>Ephemeral sessions are used when the configuration menu is sent as an ephemeral message
     * inside a group chat, visible only to the session owner.
     *
     * @param userId the Telegram user id of the administrator who opened the menu
     * @param chatId the Telegram chat id being configured
     * @return the created session
     */
    public ConfigurationContext createEphemeral(long userId, long chatId)
    {
        String hash = generateHash();
        long now = System.currentTimeMillis();
        ConfigurationContext session = new ConfigurationContext(hash, userId, chatId, now, now,
                ConfigurationPage.MAIN, null, null, true, null, false);
        return store(session);
    }

    /**
     * Replaces the stored session with a copy pointing at the given page.
     *
     * <p>If the page move leaves the Chat Linking page, any active target-chat verification id is
     * cleared from the session and the reverse index.
     *
     * @param hash the session hash
     * @param page the new page
     * @return the updated session, or {@code null} when the session no longer exists
     */
    public ConfigurationContext updatePage(String hash, ConfigurationPage page)
    {
        ConfigurationContext session = find(hash);

        if (session == null)
        {
            return null;
        }

        if (session.page() == ConfigurationPage.CHANNEL && page != ConfigurationPage.CHANNEL)
        {
            this.verificationIndex.clear(session.channelLinkVerificationCode());
            session = session.withChannelLinkVerificationCode(null);
        }

        session = session.withPage(page);
        cache().put(hash, session);

        return session;
    }

    /**
     * Stores the Telegram message id of the private configuration menu.
     *
     * @param hash the session hash
     * @param messageId the menu message id
     */
    public void updateMessageId(String hash, Integer messageId)
    {
        ConfigurationContext session = find(hash);
        if (session == null)
        {
            return;
        }
        session = session.withMessageId(messageId);
        cache().put(hash, session);
    }

    /**
     * Stores whether the menu should show a back button (whether it was opened from a prior menu).
     *
     * @param hash the session hash
     * @param showBackButton whether the menu should display a back button
     */
    public void updateShowBackButton(String hash, boolean showBackButton)
    {
        ConfigurationContext session = find(hash);
        if (session == null)
        {
            return;
        }
        session = session.withShowBackButton(showBackButton);
        cache().put(hash, session);
    }

    /**
     * Stores the ephemeral message id returned by Telegram when an ephemeral message is sent.
     *
     * @param hash the session hash
     * @param ephemeralMessageId the ephemeral message id
     */
    public void updateEphemeralMessageId(String hash, Integer ephemeralMessageId)
    {
        ConfigurationContext session = find(hash);
        if (session == null)
        {
            return;
        }
        session = session.withEphemeralMessageId(ephemeralMessageId);
        cache().put(hash, session);
    }

    /**
     * Stores the active channel verification code shown to the user and indexes it by the session.
     *
     * @param hash the session hash
     * @param verificationCode the verification code, or {@code null} to clear
     */
    public void updateChannelLinkVerificationCode(String hash, Long verificationCode)
    {
        ConfigurationContext session = find(hash);
        if (session == null)
        {
            return;
        }
        this.verificationIndex.clear(session.channelLinkVerificationCode());
        session = session.withChannelLinkVerificationCode(verificationCode);
        cache().put(hash, session);
        if (verificationCode != null)
        {
            this.verificationIndex.store(verificationCode, hash);
        }
    }

    /**
     * Returns the session currently showing the given channel verification code, so a
     * {@code /connect <code>} command can update the configuration menu that displayed it.
     *
     * @param verificationCode the verification code sent with {@code /connect}
     * @return the session, or {@code null} when no live session shows that code
     */
    public ConfigurationContext findByChannelLinkVerificationCode(long verificationCode)
    {
        String hash = this.verificationIndex.resolve(verificationCode);
        if (hash == null)
        {
            return null;
        }

        ConfigurationContext session = find(hash);
        if (session == null || session.channelLinkVerificationCode() == null
                || session.channelLinkVerificationCode() != verificationCode)
        {
            // The index outlived the session, or the session has since shown a different code.
            this.verificationIndex.clear(verificationCode);
            return null;
        }
        return session;
    }

    /**
     * Removes the session from the store and clears its verification index entry.
     *
     * @param hash the session hash
     */
    @Override
    public void invalidate(String hash)
    {
        ConfigurationContext session = cache().getIfPresent(hash);
        if (session != null)
        {
            this.verificationIndex.clear(session.channelLinkVerificationCode());
        }
        super.invalidate(hash);
    }
}
