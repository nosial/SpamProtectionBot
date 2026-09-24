package net.nosial.spb.enums;

/**
 * The tracking status of a contact that wrote to a secretary-enabled business connection.
 *
 * <p>Contacts start as {@link #UNKNOWN}: the first message is checked through Federation. The owner
 * can then approve or deny the contact; approved contacts are trusted and denied contacts are
 * automatically handled without another notification.
 */
public enum SecretaryContactStatus
{
    UNKNOWN,
    ALLOWED,
    DENIED;

    /**
     * Parses a persisted contact status.
     *
     * @param value the stored value, or {@code null}
     * @return the matching status, or {@link #UNKNOWN} when unknown
     */
    public static SecretaryContactStatus fromString(String value)
    {
        if (value == null)
        {
            return UNKNOWN;
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return UNKNOWN;
        }
    }
}
