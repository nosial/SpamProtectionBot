package net.nosial.spb.objects;

/**
 * The bot's own identity as reported by Telegram's {@code getMe} at start-up.
 *
 * <p>Handlers need it to recognise messages addressed to themselves ({@code /start@botname}), to
 * ignore their own messages, and to build deep links, so it is resolved once and shared rather
 * than looked up per update.
 *
 * @param id the bot's Telegram user id
 * @param username the bot's Telegram username without the leading '@', or an empty string when it
 *                 has none
 * @param name the bot's display name
 */
public record BotIdentity(long id, String username, String name)
{
    /**
     * Returns whether the bot has a username and can therefore be deep-linked.
     *
     * @return {@code true} when the username is not blank
     */
    public boolean hasUsername()
    {
        return this.username != null && !this.username.isBlank();
    }
}
