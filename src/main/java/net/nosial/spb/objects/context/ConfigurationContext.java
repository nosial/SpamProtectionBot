package net.nosial.spb.objects.context;

import net.nosial.spb.enums.ConfigurationPage;
import net.nosial.spb.classes.interfaces.TouchableSession;

/**
 * A short-lived, user-bound configuration session created when a suitable administrator invokes
 * {@code /start} in a group chat, or when a user invokes {@code /settings} in a group.
 *
 * <p>The session hash is included in the deep link attached to the {@code Configure} inline button
 * and in every callback query triggered by the configuration menu. The session is only valid for
 * the original caller and the target chat, preventing other users from hijacking the menu.
 *
 * @param hash the opaque session identifier
 * @param userId the Telegram user id of the administrator who started the session
 * @param chatId the Telegram chat id the session configures
 * @param createdAt epoch milliseconds when the session was created
 * @param lastUsedAt epoch milliseconds when the session was last touched
 * @param page the settings page currently displayed to the user
 * @param channelLinkVerificationCode the current target-chat verification code shown on the Chat Linking page, or {@code null}
 * @param messageId the Telegram message id of the configuration menu, or {@code null}
 * @param ephemeral whether the menu was sent as an ephemeral message visible only to the session owner
 * @param ephemeralMessageId the ephemeral message id returned by Telegram for ephemeral messages, or {@code null}
 * @param showBackButton whether the menu should display a back button (only when it was opened from
 *                       a prior menu such as the private {@code /start} menu)
 */
public record ConfigurationContext(
        String hash,
        long userId,
        long chatId,
        long createdAt,
        long lastUsedAt,
        ConfigurationPage page,
        Long channelLinkVerificationCode,
        Integer messageId,
        boolean ephemeral,
        Integer ephemeralMessageId,
        boolean showBackButton)
        implements TouchableSession
{
    /**
     * Returns a copy of this session with the given page.
     *
     * @param page the new page
     * @return the updated session
     */
    public ConfigurationContext withPage(ConfigurationPage page)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), page, this.channelLinkVerificationCode, this.messageId,
                this.ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with {@link #lastUsedAt} refreshed to now.
     *
     * @return the touched session
     */
    public ConfigurationContext touch()
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, this.channelLinkVerificationCode, this.messageId,
                this.ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with the given target-chat verification code.
     *
     * @param channelLinkVerificationCode the verification code, or {@code null} to clear it
     * @return the updated session
     */
    public ConfigurationContext withChannelLinkVerificationCode(Long channelLinkVerificationCode)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, channelLinkVerificationCode, this.messageId,
                this.ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with the given menu message id.
     *
     * @param messageId the Telegram message id, or {@code null}
     * @return the updated session
     */
    public ConfigurationContext withMessageId(Integer messageId)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, this.channelLinkVerificationCode, messageId,
                this.ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with the given ephemeral flag.
     *
     * @param ephemeral whether the menu is an ephemeral message
     * @return the updated session
     */
    public ConfigurationContext withEphemeral(boolean ephemeral)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, this.channelLinkVerificationCode, this.messageId,
                ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with the given ephemeral message id.
     *
     * @param ephemeralMessageId the ephemeral message id, or {@code null}
     * @return the updated session
     */
    public ConfigurationContext withEphemeralMessageId(Integer ephemeralMessageId)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, this.channelLinkVerificationCode, this.messageId,
                this.ephemeral, this.ephemeralMessageId, this.showBackButton);
    }

    /**
     * Returns a copy of this session with the given back-button flag.
     *
     * @param showBackButton whether the menu should display a back button
     * @return the updated session
     */
    public ConfigurationContext withShowBackButton(boolean showBackButton)
    {
        return new ConfigurationContext(this.hash, this.userId, this.chatId, this.createdAt,
                System.currentTimeMillis(), this.page, this.channelLinkVerificationCode, this.messageId,
                this.ephemeral, this.ephemeralMessageId, showBackButton);
    }
}
