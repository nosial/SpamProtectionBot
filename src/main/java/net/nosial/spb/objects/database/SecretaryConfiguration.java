package net.nosial.spb.objects.database;

import net.nosial.spb.enums.ScanningBehavior;

/**
 * Persistent configuration for a user who enabled the bot as their personal secretary through a
 * Telegram Business connection.
 *
 * <p>The record exists only while the business connection is enabled: starting secretary mode
 * inserts a default configuration and disconnecting removes it. The {@code businessConnectionId}
 * lets a {@code business_message} update be routed back to the owning user.
 *
 * @param userId the Telegram user id of the account the bot manages
 * @param businessConnectionId the Telegram business connection id the configuration belongs to
 * @param behavior how messages from unknown contacts are handled
 * @param privacyMode whether scanning minimizes optional Federation data
 * @param scanningEnabled whether first-contact message content is sent to Federation for scanning;
 *                        when off, only the sender's Federation record is queried
 */
public record SecretaryConfiguration(
        long userId,
        String businessConnectionId,
        ScanningBehavior behavior,
        boolean privacyMode,
        boolean scanningEnabled)
{
    /**
     * Creates the configuration a user's secretary mode starts with.
     *
     * <p>Content scanning starts disabled: a private message falsely flagged as spam could
     * otherwise end up in a Federation report. The sender is still queried against Federation.
     *
     * @param userId the Telegram user whose account the bot manages
     */
    public SecretaryConfiguration(long userId)
    {
        this(userId, "", ScanningBehavior.STRICT, false, false);
    }

    public SecretaryConfiguration
    {
        if (behavior != ScanningBehavior.PASSIVE && behavior != ScanningBehavior.STRICT)
        {
            throw new IllegalArgumentException("Secretary Mode supports only Passive and Strict behavior");
        }
    }
}