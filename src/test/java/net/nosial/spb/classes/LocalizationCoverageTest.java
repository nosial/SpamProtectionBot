package net.nosial.spb.classes;

import net.nosial.spb.objects.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every translation key the code asks for exists in the language file.
 *
 * <p>A missing key is not a crash: {@link LanguageManager} serves the key's own name, so the bot
 * cheerfully sends {@code report.usage} to a user instead of the sentence it should have sent.
 * That is exactly the kind of defect that survives code review and reaches production, so it is
 * checked here rather than left to be noticed in a chat.
 *
 * <p>The scan reads the source tree for literal {@code get(lang, "section", "key")} calls. Keys
 * assembled at runtime — a page name concatenated with a suffix, for instance — cannot be seen
 * this way and are covered by {@link #everyPageKeyIsTranslated()} instead.
 */
class LocalizationCoverageTest
{
    /** {@code get(..., "section", "key"} and {@code get(..., "section", "key"}. */
    private static final Pattern LOOKUP = Pattern.compile(
            "\\bget(?:Raw)?\\s*\\(\\s*[^,()]+,\\s*\"([a-z0-9_]+)\"\\s*,\\s*\"([a-z0-9_.]+)\"\\s*(?=[,)])");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private final LanguageManager languages = new LanguageManager("en");

    /**
     * Returns whether the manager has a real translation for the key rather than its own name.
     *
     * @param section the section
     * @param key the key within the section
     * @return {@code true} when a translation exists
     */
    private boolean isTranslated(String section, String key)
    {
        String dotted = section + "." + key;
        return !dotted.equals(this.languages.get(this.languages.defaultLanguage(), section, key));
    }

    @Test
    @DisplayName("every translation key used in the source exists in en.yml")
    void everyUsedKeyIsTranslated() throws IOException
    {
        Set<String> missing = new TreeSet<>();
        int checked = 0;

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT))
        {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList())
            {
                Matcher matcher = LOOKUP.matcher(Files.readString(source));
                while (matcher.find())
                {
                    checked++;
                    if (!isTranslated(matcher.group(1), matcher.group(2)))
                    {
                        missing.add(matcher.group(1) + "." + matcher.group(2)
                                + "  (" + source.getFileName() + ")");
                    }
                }
            }
        }

        assertTrue(checked > 200, "expected the scan to find the translation calls, found " + checked);
        assertEquals(Set.of(), missing, "translation keys used in code but missing from en.yml");
    }

    @Test
    @DisplayName("every help and settings page has a title and a body")
    void everyPageKeyIsTranslated()
    {
        Set<String> missing = new TreeSet<>();

        for (net.nosial.spb.enums.HelpPage page : net.nosial.spb.enums.HelpPage.values())
        {
            String title = page.title(this.languages, this.languages.defaultLanguage());
            String body = page.body(this.languages, this.languages.defaultLanguage());

            if (title.startsWith("help_pages."))
            {
                missing.add(title);
            }
            if (body.startsWith("help_pages."))
            {
                missing.add(body);
            }
        }

        for (net.nosial.spb.enums.SettingsPage page : net.nosial.spb.enums.SettingsPage.values())
        {
            String title = page.title(this.languages, this.languages.defaultLanguage());
            String body = page.body(this.languages, this.languages.defaultLanguage());

            if (title.startsWith("help_pages."))
            {
                missing.add(title);
            }
            if (body.startsWith("help_pages."))
            {
                missing.add(body);
            }
        }

        assertEquals(Set.of(), missing, "help or settings pages without translated text");
    }

    @Test
    @DisplayName("every discovered language declares how to show itself")
    void everyOfferedLanguageIsDisplayable()
    {
        // A file that omits localization_properties is skipped rather than offered, so anything
        // reaching the settings menu can be drawn as a button.
        for (Language language : this.languages.availableLanguages())
        {
            assertFalse(language.name().isBlank(), language.code() + " has no name");
            assertFalse(language.emoji().isBlank(), language.code() + " has no emoji");
            assertFalse(language.label().isBlank(), language.code() + " has no button label");
        }
    }

    @Test
    @DisplayName("every language file declares how it wants to be shown")
    void everyFileDeclaresItself() throws IOException
    {
        // A file without localization_properties is skipped rather than offered, so a translation
        // that forgets the section would silently never appear in the picker.
        Set<String> undeclared = new TreeSet<>();

        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/languages")))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList())
            {
                String content = Files.readString(file);
                if (!content.contains("localization_properties:"))
                {
                    undeclared.add(file.getFileName().toString());
                }
            }
        }

        assertEquals(Set.of(), undeclared, "language files with no localization_properties section");
    }

    @Test
    @DisplayName("every shipped language file is actually discovered")
    void everyShippedFileIsDiscovered() throws IOException
    {
        Set<String> onDisk = new TreeSet<>();

        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/languages")))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList())
            {
                String name = file.getFileName().toString();
                onDisk.add(name.substring(0, name.length() - ".yml".length()));
            }
        }

        Set<String> discovered = new TreeSet<>(this.languages.availableLanguages().stream()
                .map(Language::code)
                .toList());

        assertTrue(discovered.containsAll(onDisk),
                "shipped but not discovered: " + onDisk.stream().filter(c -> !discovered.contains(c)).toList());
    }

    @Test
    @DisplayName("no user-facing text is hard-coded in the handlers")
    void handlersDoNotHardCodeText() throws IOException
    {
        // A handler sending a bare sentence instead of a lookup is how a translation gap gets
        // introduced, so the shape is checked rather than trusted. Known-safe literals (markup,
        // callback data, separators, formats) are allowed through.
        Pattern sentence = Pattern.compile("\\.text\\(\\s*\"([^\"]{15,})\"");
        List<String> offenders = new java.util.ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT.resolve("net/nosial/spb/handlers")))
        {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList())
            {
                Matcher matcher = sentence.matcher(Files.readString(source));
                while (matcher.find())
                {
                    offenders.add(source.getFileName() + ": " + matcher.group(1));
                }
            }
        }

        assertEquals(List.of(), offenders, "user-facing text sent without going through the language manager");
    }
}
