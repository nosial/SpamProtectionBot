package net.nosial.spb.enums;

import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;

/**
 * One page in the private {@code /help} guide.
 */
public enum HelpPage
{
    MAIN("main"),
    SECRETARY_MODE("secretary_mode"),
    CHAT_PROTECTION("chat_protection"),
    SETTINGS("settings"),
    FEDERATION_COMMANDS("federation_commands"),
    OPERATORS("operator_usage");

    private final String key;

    /**
     * Constructs a HelpPage instance with the specified key.
     *
     * @param key the unique key identifying this help page
     */
    HelpPage(String key)
    {
        this.key = key;
    }

    /**
     * Retrieves the title for the help page in the specified language.
     *
     * @param languageManager the language manager responsible for retrieving translations
     * @param lang the language in which the title should be retrieved
     * @return the localized title of the help page
     */
    public String title(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", this.key + "_title");
    }

    /**
     * Retrieves the body content for the help page in the specified language.
     *
     * @param languageManager the language manager responsible for retrieving translations
     * @param lang the language in which the body content should be retrieved
     * @return the localized body content of the help page
     */
    public String body(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", this.key + "_body");
    }
}