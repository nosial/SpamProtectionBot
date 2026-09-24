package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.enums.JoinProtectionBehavior;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.ChatConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code chat_configuration} table.
 *
 * <p>A row exists only while protection is enabled in a chat: {@link #enableChatConfiguration}
 * creates it with the schema's defaults and {@link #deleteChatConfiguration} removes it. Every
 * setting is changed individually by chat id, so a write only ever touches the columns it means
 * to. Hot lookups are backed by an in-memory {@link Cache} so checking whether the bot is enabled
 * in a chat does not hit SQLite on every message.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class ChatConfigurationManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatConfigurationManager.class);

    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long CACHE_EXPIRY_MINUTES = 5;

    private final Database database;
    private final boolean defaultPrivacyMode;
    private final Cache<Long, ChatConfiguration> cache;
    private final Cache<Long, Long> channelLinkCache;
    private final Cache<Long, Long> verificationCodeCache;

    /**
     * Creates a new chat configuration manager over the given database.
     *
     * @param database the database holding the {@code chat_configuration} table
     * @param defaultPrivacyMode the privacy mode new chats start with, from {@code bot.privacy_mode}
     */
    public ChatConfigurationManager(Database database, boolean defaultPrivacyMode)
    {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.defaultPrivacyMode = defaultPrivacyMode;
        this.cache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.channelLinkCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.verificationCodeCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Returns the configuration for the given Telegram chat id.
     *
     * <p>Cache misses load the configuration from the database. A transient database read failure
     * is logged and reported as absent; a cached absence refreshes from the database once the cache entry expires.
     *
     * @param chatId the Telegram chat id
     * @return the configuration, or {@link Optional#empty()} when no record exists
     */
    public Optional<ChatConfiguration> getChatConfiguration(long chatId)
    {
        return Optional.ofNullable(this.cache.get(chatId, this::loadChatConfiguration));
    }

    /**
     * Returns whether a configuration exists for the given Telegram chat id.
     *
     * @param chatId the Telegram chat id
     * @return {@code true} when a record exists
     */
    public boolean chatConfigurationExists(long chatId)
    {
        return getChatConfiguration(chatId).isPresent();
    }

    /**
     * Returns the configuration a chat without a row is treated as having.
     *
     * @param chatId the Telegram chat id
     * @return a disabled chat configuration
     */
    public ChatConfiguration defaultConfiguration(long chatId)
    {
        return new ChatConfiguration(chatId, this.defaultPrivacyMode);
    }

    /**
     * Resolves the configuration for the given chat, returning the stored configuration or a
     * default when none exists.
     *
     * @param chatId the Telegram chat id
     * @return the chat configuration (never {@code null})
     */
    public ChatConfiguration resolve(long chatId)
    {
        return getChatConfiguration(chatId).orElseGet(() -> defaultConfiguration(chatId));
    }

    /**
     * Enables protection in a chat, creating its row with the schema's defaults and this bot's
     * privacy-mode default when it has none.
     *
     * @param chatId the Telegram chat id
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void enableChatConfiguration(long chatId) throws DatabaseException
    {
        this.database.execute("INSERT INTO chat_configuration (chat_id, enabled, privacy_mode) VALUES (?, 1, ?) "
                + "ON CONFLICT(chat_id) DO UPDATE SET enabled = 1", statement ->
        {
            statement.setLong(1, chatId);
            statement.setInt(2, this.defaultPrivacyMode ? 1 : 0);
        });
        this.cache.remove(chatId);
    }

    /**
     * Removes the configuration for the given Telegram chat id, if any.
     *
     * @param chatId the Telegram chat id
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void deleteChatConfiguration(long chatId) throws DatabaseException
    {
        this.database.execute("DELETE FROM chat_configuration WHERE chat_id = ?",
                statement -> statement.setLong(1, chatId));
        this.cache.remove(chatId);
    }

    /**
     * Updates the scanning-enabled status for the specified chat.
     *
     * @param chatId the Telegram chat id
     * @param enabled {@code true} to enable scanning, {@code false} to disable scanning
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setScanningEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "scanning_enabled = ?", enabled);
    }

    /**
     * Updates the scanning behavior setting for the specified Telegram chat.
     *
     * @param chatId the Telegram chat id
     * @param behavior the new scanning behavior to apply
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setScanningBehavior(long chatId, ScanningBehavior behavior) throws DatabaseException
    {
        update(chatId, "scanning_behavior = ?", behavior);
    }

    /**
     * Updates the scanning notifications setting for the specified Telegram chat.
     *
     * @param chatId the Telegram chat id
     * @param enabled {@code true} to enable scanning notifications, {@code false} to disable them
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setScanningNotificationsEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "scanning_notifications_enabled = ?", enabled);
    }

    /**
     * Configures the status of join protection for a specific chat.
     *
     * @param chatId The unique identifier of the chat for which join protection is being set.
     * @param enabled A boolean value indicating whether join protection should be enabled (true) or disabled (false).
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setJoinProtectionEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "join_protection_enabled = ?", enabled);
    }

    /**
     * Sets the join protection behavior for a specific chat.
     *
     * @param chatId The unique identifier of the chat where the join protection behavior will be set.
     * @param behavior The desired join protection behavior to be applied to the chat.
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setJoinProtectionBehavior(long chatId, JoinProtectionBehavior behavior) throws DatabaseException
    {
        update(chatId, "join_protection_behavior = ?", behavior);
    }

    /**
     * Enables or disables join protection notifications for a specific chat.
     *
     * @param chatId The unique identifier of the chat for which the setting is being updated.
     * @param enabled A boolean indicating whether join protection notifications should be enabled (true) or disabled (false).
     * @throws DatabaseException If an error occurs while updating the database.
     */
    public void setJoinProtectionNotificationsEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "join_protection_notifications_enabled = ?", enabled);
    }

    /**
     * Sets the privacy mode for a specific chat.
     *
     * @param chatId  The unique identifier of the chat for which the privacy mode is being set.
     * @param privacyMode  A boolean value where true enables privacy mode and false disables it.
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setPrivacyMode(long chatId, boolean privacyMode) throws DatabaseException
    {
        update(chatId, "privacy_mode = ?", privacyMode);
    }

    /**
     * Enables or disables reporting for a specific chat.
     *
     * @param chatId  the unique identifier of the chat for which reporting is being modified
     * @param enabled a boolean indicating whether reporting should be enabled (true) or disabled (false)
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setReportingEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "reporting_enabled = ?", enabled);
    }

    /**
     * Enables or disables reporting notifications for a specific chat.
     *
     * @param chatId  The unique identifier of the chat for which reporting notifications should be configured.
     * @param enabled A boolean indicating whether reporting notifications should be enabled (true) or disabled (false).
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setReportingNotificationsEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "reporting_notifications_enabled = ?", enabled);
    }

    /**
     * Updates the moderator notifications setting for a specific chat.
     *
     * @param chatId The unique identifier of the chat for which the moderator notifications setting is to be updated.
     * @param enabled A boolean value indicating whether moderator notifications should be enabled (true) or disabled (false).
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setModeratorNotificationsEnabled(long chatId, boolean enabled) throws DatabaseException
    {
        update(chatId, "moderator_notifications_enabled = ?", enabled);
    }

    /**
     * Sets or clears the code a notification chat sends with {@code /connect} to link itself.
     *
     * @param chatId the Telegram chat id
     * @param verificationCode the new code, or {@code null} to clear it
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setChannelLinkVerificationCode(long chatId, Long verificationCode) throws DatabaseException
    {
        update(chatId, "channel_link_verification_code = ?", verificationCode);
        if (verificationCode != null)
        {
            this.verificationCodeCache.put(verificationCode, chatId);
        }
    }

    /**
     * Links a notification chat, consuming the verification code that authorised it.
     *
     * @param chatId the Telegram chat id
     * @param channelLinkId the notification chat being linked
     * @param channelLinkThreadId the forum topic notifications are posted in, or {@code null}
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void linkChannel(long chatId, long channelLinkId, Long channelLinkThreadId) throws DatabaseException
    {
        update(chatId, "channel_link_id = ?, channel_link_thread_id = ?, channel_link_verification_code = NULL",
                channelLinkId, channelLinkThreadId);
        this.channelLinkCache.put(channelLinkId, chatId);
    }

    /**
     * Unlinks the notification chat and discards any pending verification code.
     *
     * @param chatId the Telegram chat id
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void unlinkChannel(long chatId) throws DatabaseException
    {
        update(chatId, "channel_link_id = NULL, channel_link_verification_code = NULL");
    }

    /**
     * Returns the configuration whose linked notification chat id matches the given value.
     *
     * <p>The reverse mapping from channel link id to chat id is cached because this lookup cannot
     * use the primary chat-id cache; the resolved configuration itself still comes from the
     * primary cache. A cached mapping that no longer matches the resolved configuration is
     * dropped, so writes never have to track the value they replace.
     *
     * @param channelLinkId the linked notification chat id to search for
     * @return the matching configuration, or {@link Optional#empty()} when none exists
     */
    public Optional<ChatConfiguration> getChatConfigurationByChannelLinkId(long channelLinkId)
    {
        Long chatId = this.channelLinkCache.get(channelLinkId,
                key -> loadChatId("channel_link_id", key));
        Optional<ChatConfiguration> configuration = chatId == null ? Optional.empty() : getChatConfiguration(chatId);
        if (configuration.isPresent() && Objects.equals(configuration.get().channelLinkId(), channelLinkId))
        {
            return configuration;
        }
        this.channelLinkCache.remove(channelLinkId);
        return Optional.empty();
    }

    /**
     * Returns the configuration whose notification-chat verification code matches the given value.
     *
     * <p>Like {@link #getChatConfigurationByChannelLinkId(long)}, the code-to-chat mapping is
     * cached and verified against the resolved configuration before it is returned.
     *
     * @param verificationCode the notification-chat verification code to search for
     * @return the matching configuration, or {@link Optional#empty()} when none exists
     */
    public Optional<ChatConfiguration> getChatConfigurationByChannelLinkVerificationCode(long verificationCode)
    {
        Long chatId = this.verificationCodeCache.get(verificationCode,
                key -> loadChatId("channel_link_verification_code", key));
        Optional<ChatConfiguration> configuration = chatId == null ? Optional.empty() : getChatConfiguration(chatId);
        if (configuration.isPresent()
                && Objects.equals(configuration.get().channelLinkVerificationCode(), verificationCode))
        {
            return configuration;
        }
        this.verificationCodeCache.remove(verificationCode);
        return Optional.empty();
    }

    /**
     * Applies column assignments to one chat's row and drops its cached configuration.
     *
     * @param chatId the Telegram chat id
     * @param assignments the {@code SET} clause, with a {@code ?} per value
     * @param values the values, in placeholder order; booleans are stored as {@code 0}/{@code 1} and enums by name
     * @throws DatabaseException If there is an error while updating the database.
     */
    private void update(long chatId, String assignments, Object... values) throws DatabaseException
    {
        this.database.execute("UPDATE chat_configuration SET " + assignments + " WHERE chat_id = ?", statement ->
        {
            for (int i = 0; i < values.length; i++)
            {
                Object value = values[i];
                if (value instanceof Boolean flag)
                {
                    value = flag ? 1 : 0;
                }
                else if (value instanceof Enum<?> constant)
                {
                    value = constant.name();
                }
                statement.setObject(i + 1, value);
            }
            statement.setLong(values.length + 1, chatId);
        });
        this.cache.remove(chatId);
    }

    /**
     * Loads the chat id whose given column holds the given value, used to fill reverse-lookup
     * cache misses.
     *
     * @param column the column to match
     * @param value the value to match
     * @return the owning chat id, or {@code null} when none or the read failed
     */
    private Long loadChatId(String column, long value)
    {
        try
        {
            return this.database.queryOne("SELECT chat_id FROM chat_configuration WHERE " + column + " = ?",
                    statement -> statement.setLong(1, value), result -> result.getLong("chat_id")).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load chat configuration by {} {}: {}", column, value, e.getMessage());
            return null;
        }
    }

    /**
     * Loads a chat configuration from the database, used to fill cache misses.
     *
     * @param chatId the Telegram chat id
     * @return the configuration, or {@code null} when no record exists or the read failed
     */
    private ChatConfiguration loadChatConfiguration(long chatId)
    {
        try
        {
            return this.database.queryOne("SELECT * FROM chat_configuration WHERE chat_id = ?",
                    statement -> statement.setLong(1, chatId), ChatConfiguration::new).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load chat configuration for chat {}: {}", chatId, e.getMessage());
            return null;
        }
    }
}
