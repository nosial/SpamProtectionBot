package net.nosial.spb.objects.database;

import java.sql.SQLException;
import java.sql.ResultSet;
import net.nosial.spb.enums.JoinProtectionBehavior;
import net.nosial.spb.enums.ScanningBehavior;

/**
 * Persistent configuration for a chat where the bot has administrator permissions.
 *
 * @param chatId the Telegram chat id
 * @param enabled whether the bot's chat-specific features are enabled
 * @param scanningEnabled whether content scanning and author-user checks are active
 * @param scanningBehavior how scanning results and users are handled
 * @param joinProtectionEnabled whether members are checked when they join
 * @param joinProtectionBehavior how recommended member blocks are handled on joining
 * @param joinProtectionNotificationsEnabled whether join-protection events notify moderators
 * @param privacyMode whether scanning minimizes optional Federation data
 * @param reportingEnabled whether the {@code /report} command is enabled
 * @param moderatorNotificationsEnabled whether eligible moderators receive private notifications
 * @param scanningNotificationsEnabled whether scanning events produce moderator notifications
 * @param reportingNotificationsEnabled whether submitted reports produce moderator notifications
 * @param channelLinkId the linked notification chat or channel id, or {@code null}
 * @param channelLinkVerificationCode the one-time code used to link a notification chat, or {@code null}
 * @param channelLinkThreadId the forum topic id in the linked notification chat, or {@code null}
 */
public record ChatConfiguration(
        long chatId,
        boolean enabled,
        boolean scanningEnabled,
        ScanningBehavior scanningBehavior,
        boolean joinProtectionEnabled,
        JoinProtectionBehavior joinProtectionBehavior,
        boolean joinProtectionNotificationsEnabled,
        boolean privacyMode,
        boolean reportingEnabled,
        boolean moderatorNotificationsEnabled,
        boolean scanningNotificationsEnabled,
        boolean reportingNotificationsEnabled,
        Long channelLinkId,
        Long channelLinkVerificationCode,
        Long channelLinkThreadId)
{
    /**
     * Creates the configuration a chat starts with before anybody has changed anything.
     *
     * <p>Protection is off until an owner turns it on; once on, it protects as strongly as it
     * can, because a chat that asked for protection wants protection. These values mirror the
     * column defaults in {@code sql/chat_configuration.sql}, which new rows are created with.
     *
     * @param chatId the Telegram chat id
     * @param privacyMode whether new chats minimise what they send to Federation, from
     *                    {@code bot.privacy_mode}
     */
    public ChatConfiguration(long chatId, boolean privacyMode)
    {
        this(chatId, false, true, ScanningBehavior.STRICT, true, JoinProtectionBehavior.STRICT, true,
                privacyMode, true, true, true, true, null, null, null);
    }

    /**
     * Reads a configuration out of its database row.
     *
     * <p>The row is the only argument because the row already says everything: rather than making
     * the caller pull fifteen columns out and pass them along in the right order, the constructor
     * reads the ones it owns.
     *
     * @param row a {@code chat_configuration} row, positioned on the record to read
     * @throws SQLException If a column cannot be read
     */
    public ChatConfiguration(ResultSet row) throws SQLException
    {
        this(row.getLong("chat_id"),
                row.getInt("enabled") != 0,
                row.getInt("scanning_enabled") != 0,
                storedScanningBehavior(row),
                row.getInt("join_protection_enabled") != 0,
                JoinProtectionBehavior.fromString(row.getString("join_protection_behavior")),
                row.getInt("join_protection_notifications_enabled") != 0,
                row.getInt("privacy_mode") != 0,
                row.getInt("reporting_enabled") != 0,
                row.getInt("moderator_notifications_enabled") != 0,
                row.getInt("scanning_notifications_enabled") != 0,
                row.getInt("reporting_notifications_enabled") != 0,
                nullableLong(row, "channel_link_id"),
                nullableLong(row, "channel_link_verification_code"),
                nullableLong(row, "channel_link_thread_id"));
    }

    /**
     * Reads the stored scanning behavior, correcting a level that cannot work.
     *
     * <p>Passive scanning only notifies, so with notifications off it would do nothing at all.
     * A chat in that state is read back as moderate rather than silently unprotected.
     *
     * @param row the row to read
     * @return the usable scanning behavior
     * @throws SQLException If a column cannot be read
     */
    private static ScanningBehavior storedScanningBehavior(ResultSet row) throws SQLException
    {
        ScanningBehavior stored = ScanningBehavior.fromString(row.getString("scanning_behavior"));
        boolean notifications = row.getInt("scanning_notifications_enabled") != 0;
        return stored == ScanningBehavior.PASSIVE && !notifications ? ScanningBehavior.MODERATE : stored;
    }

    /**
     * Reads a column that may be SQL NULL.
     *
     * @param row the row to read
     * @param column the column name
     * @return the value, or {@code null} when the column is NULL
     * @throws SQLException If the column cannot be read
     */
    private static Long nullableLong(ResultSet row, String column) throws SQLException
    {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }
}
