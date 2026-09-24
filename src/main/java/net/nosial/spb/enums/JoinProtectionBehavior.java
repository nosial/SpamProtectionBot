package net.nosial.spb.enums;

/**
 * The action taken when Federation recommends blocking a member who has just joined a chat.
 */
public enum JoinProtectionBehavior
{
    // Declared least to most strict, and kept in the same order as ScanningBehavior: the settings
    // menu lists the levels by iterating values(), so the declaration order is what a moderator
    // sees. Persistence stores the name, never the ordinal, so this order is safe to change.
    PASSIVE,
    MODERATE,
    STRICT;

    /**
     * Parses a persisted protection behavior.
     *
     * @param value the stored value, or {@code null}
     * @return the matching level, or {@link #PASSIVE} when unknown
     */
    public static JoinProtectionBehavior fromString(String value)
    {
        if (value == null)
        {
            return PASSIVE;
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return PASSIVE;
        }
    }
}
