package net.nosial.spb.objects;

import java.util.Objects;

/**
 * A language the bot can speak, as declared by its own translation file.
 *
 * <p>Languages are not a fixed list in code. Each file under {@code languages/} names itself with
 * an ISO 639-1 code and declares how it wants to be shown in its own
 * {@code localization_properties} section, so adding a translation is adding one file and
 * restarting. Nothing else knows the set of languages: the settings menu is built from whatever
 * {@link net.nosial.spb.classes.LanguageManager} found.
 *
 * @param code the ISO 639-1 code, lowercase, taken from the file name
 * @param name the language's name written in that language, for the settings menu
 * @param emoji the flag or other emoji shown beside the name
 */
public record Language(String code, String name, String emoji)
{
    /**
     * Rejects a language that could not be shown to a user.
     */
    public Language
    {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(emoji, "emoji must not be null");

        if (code.isBlank())
        {
            throw new IllegalArgumentException("code must not be blank");
        }

        if (name.isBlank())
        {
            throw new IllegalArgumentException("name must not be blank for language '" + code + "'");
        }

        code = code.toLowerCase();
    }

    /**
     * Returns the label shown on the language's button, emoji first.
     *
     * @return the display label
     */
    public String label()
    {
        return this.emoji + " " + this.name;
    }

    /**
     * Returns whether this language answers to the given code.
     *
     * <p>Matching accepts a regional variant: a client reporting {@code pt-BR} is served by
     * {@code pt} when that is what exists.
     *
     * @param languageCode the code to test, possibly with a region suffix or {@code null}
     * @return {@code true} when this language should serve that code
     */
    public boolean matches(String languageCode)
    {
        if (languageCode == null || languageCode.isBlank())
        {
            return false;
        }

        String normalized = languageCode.strip().toLowerCase();
        if (this.code.equals(normalized))
        {
            return true;
        }

        int region = normalized.indexOf('-');
        return region > 0 && this.code.equals(normalized.substring(0, region));
    }
}
