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
 */
public record SecretaryConfiguration(
        long userId,
        String businessConnectionId,
        ScanningBehavior behavior,
        boolean privacyMode)
{
    /**
     * Creates the configuration a user's secretary mode starts with.
     *
     * <p>Secretary mode always scans with the sender's identity and always acts on what it finds,
     * so there is nothing to choose here beyond who it belongs to.
     *
     * @param userId the Telegram user whose account the bot manages
     */
    public SecretaryConfiguration(long userId)
    {
        this(userId, "", ScanningBehavior.STRICT, false);
    }

    public SecretaryConfiguration
    {
        if (behavior != ScanningBehavior.PASSIVE && behavior != ScanningBehavior.STRICT)
        {
            throw new IllegalArgumentException("Secretary Mode supports only Passive and Strict behavior");
        }
    }
}