package net.nosial.spb.enums;

import java.util.List;

/**
 * One of the pages shown in the configure-settings view of the configuration menu.
 */
public enum ConfigurationPage
{
    MAIN,
    SCANNING,
    JOIN_PROTECTION,
    REPORTING,
    PRIVACY,
    MODERATOR_NOTIFICATIONS,
    CHANNEL,
    LANGUAGE;

    private static final List<ConfigurationPage> ORDERED = List.of(SCANNING, JOIN_PROTECTION, REPORTING, PRIVACY, MODERATOR_NOTIFICATIONS, CHANNEL);

    /**
     * Returns the previous settings page in cyclic order.
     *
     * @return the previous page
     */
    public ConfigurationPage previous()
    {
        int index = ORDERED.indexOf(this);
        if (index <= 0)
        {
            return ORDERED.get(ORDERED.size() - 1);
        }
        return ORDERED.get(index - 1);
    }

    /**
     * Returns the next settings page in cyclic order.
     *
     * @return the next page
     */
    public ConfigurationPage next()
    {
        int index = ORDERED.indexOf(this);
        if (index < 0 || index >= ORDERED.size() - 1)
        {
            return ORDERED.get(0);
        }
        return ORDERED.get(index + 1);
    }
}
