package net.nosial.spb.enums;

import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;

/**
 * One of the feature or setting sub-pages in the Settings help menu.
 */
public enum SettingsPage
{
    SCANNING("scanning"),
    JOIN_PROTECTION("join_protection"),
    REPORTING("reporting"),
    PRIVACY("privacy"),
    MODERATOR_NOTIFICATIONS("moderator_notifications"),
    CHANNEL_LINKING("channel_linking");

    private final String key;

    /**
     * Constructs a SettingsPage instance with the specified key.
     *
     * @param key the unique key identifying this settings page
     */
    SettingsPage(String key)
    {
        this.key = key;
    }

    /**
     * Retrieves the localized title for this settings page from the language manager.
     *
     * @param languageManager the language manager used to fetch the localized title
     * @param lang the language in which the title should be retrieved
     * @return the localized title for the settings page
     */
    public String title(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", "settings_" + this.key + "_title");
    }

    /**
     * Retrieves the localized body text for this settings page from the language manager.
     *
     * @param languageManager the language manager used to fetch the localized body text
     * @param lang the language in which the body text should be retrieved
     * @return the localized body text for the settings page
     */
    public String body(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", "settings_" + this.key + "_body");
    }
}
