package net.nosial.spb.enums;

import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;

/**
 * One of the command sub-pages in the Operator Usage help menu.
 */
public enum OperatorPage
{
    AUTHENTICATION("authentication"),
    BLACKLIST("blacklist"),
    LINKING("linking"),
    REPORT_ASSIGNMENTS("report_assignments");

    private final String key;

    /**
     * Constructs an OperatorPage instance with the specified key.
     *
     * @param key the unique key identifying this operator help page
     */
    OperatorPage(String key)
    {
        this.key = key;
    }

    /**
     * Retrieves the localized title for the operator help page represented by this enum instance.
     *
     * @param languageManager the language manager used to fetch the localized string
     * @param lang the language in which the title should be retrieved
     * @return the localized title for the operator help page
     */
    public String title(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", "operator_" + this.key + "_title");
    }

    /**
     * Retrieves the localized body text for the operator help page represented by this enum instance.
     *
     * @param languageManager the language manager used to fetch the localized string
     * @param lang the language in which the body text should be retrieved
     * @return the localized body text for the operator help page
     */
    public String body(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "help_pages", "operator_" + this.key + "_body");
    }
}
