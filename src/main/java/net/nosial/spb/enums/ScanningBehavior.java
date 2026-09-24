package net.nosial.spb.enums;

/**
 * The moderation level applied to content and message-author user scanning results.
 */
public enum ScanningBehavior
{
    // Declared least to most strict; the settings menu lists the levels in this order.
    PASSIVE,
    MODERATE,
    STRICT;

    /**
     * Parses a persisted moderation level.
     *
     * @param value the stored value, or {@code null}
     * @return the matching level, or {@link #PASSIVE} when unknown
     */
    public static ScanningBehavior fromString(String value)
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
