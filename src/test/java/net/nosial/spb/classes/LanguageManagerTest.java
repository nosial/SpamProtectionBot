package net.nosial.spb.classes;

import net.nosial.spb.objects.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for discovering languages from their files and serving their translations.
 *
 * <p>The fixture {@code languages/zz.yml} exists only on the test classpath and is named in no
 * Java source. Every assertion about it is therefore an assertion that languages really are
 * discovered rather than declared.
 */
class LanguageManagerTest
{
    private final LanguageManager languages = new LanguageManager("en");

    @Nested
    @DisplayName("Discovering languages")
    class Discovery
    {
        @Test
        @DisplayName("a language nothing in code mentions is discovered from its file")
        void discoversUndeclaredLanguage()
        {
            Language fixture = LanguageManagerTest.this.languages.resolve("zz");

            assertNotEquals(null, fixture, "languages/zz.yml should have been discovered");
            assertEquals("zz", fixture.code());
            assertEquals("Zzyzx", fixture.name(), "the name comes from localization_properties");
            assertEquals("🧪", fixture.emoji(), "the emoji comes from localization_properties");
        }

        @Test
        @DisplayName("every discovered language is offered, ordered by code")
        void offersEveryDiscoveredLanguage()
        {
            List<String> codes = LanguageManagerTest.this.languages.availableLanguages().stream()
                    .map(Language::code)
                    .toList();

            // Not pinned to an exact list: shipped translations come and go without this test
            // needing to know about them.
            assertEquals(codes.stream().sorted().distinct().toList(), codes, "codes should be sorted and unique");
            assertTrue(codes.contains("en"), "the shipped default should be offered: " + codes);
            assertTrue(codes.contains("zz"), "the test-only fixture should be offered: " + codes);
            codes.forEach(code -> assertNotEquals(null, LanguageManagerTest.this.languages.resolve(code),
                    "offered language " + code + " should resolve"));
        }

        @Test
        @DisplayName("a language with no file is not offered")
        void unknownLanguagesAreAbsent()
        {
            assertNull(LanguageManagerTest.this.languages.resolve("qq"));
            assertFalse(LanguageManagerTest.this.languages.isAvailable(new Language("qq", "Nope", "?")));
        }

        @Test
        @DisplayName("English is loaded in full")
        void loadsEnglish()
        {
            Language english = LanguageManagerTest.this.languages.resolve("en");

            assertTrue(LanguageManagerTest.this.languages.isAvailable(english));
            assertTrue(LanguageManagerTest.this.languages.keyCount(english) > 400,
                    "expected the full English catalogue, got "
                            + LanguageManagerTest.this.languages.keyCount(english));
        }

        @Test
        @DisplayName("a default language with no file is refused rather than serving key names")
        void refusesUntranslatedDefault()
        {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new LanguageManager("qq"));

            assertTrue(e.getMessage().contains("qq"), e.getMessage());
        }

        @Test
        @DisplayName("the default language is the configured one")
        void reportsTheDefault()
        {
            assertEquals("en", LanguageManagerTest.this.languages.defaultLanguage().code());
            assertEquals("zz", new LanguageManager("zz").defaultLanguage().code());
        }
    }

    @Nested
    @DisplayName("Resolving codes")
    class Resolving
    {
        @Test
        @DisplayName("a regional variant resolves to its base language")
        void resolvesRegionalVariants()
        {
            assertEquals("en", LanguageManagerTest.this.languages.resolve("en-GB").code());
            assertEquals("en", LanguageManagerTest.this.languages.resolve("EN").code());
        }

        @Test
        @DisplayName("an unknown or missing code falls back to the default")
        void fallsBackToDefault()
        {
            assertEquals("en", LanguageManagerTest.this.languages.resolveOrDefault("qq").code());
            assertEquals("en", LanguageManagerTest.this.languages.resolveOrDefault(null).code());
            assertEquals("en", LanguageManagerTest.this.languages.resolveOrDefault("  ").code());
        }

        @Test
        @DisplayName("an unknown code resolves to nothing when no fallback was asked for")
        void resolvesNothingWithoutFallback()
        {
            assertNull(LanguageManagerTest.this.languages.resolve("qq"));
            assertNull(LanguageManagerTest.this.languages.resolve(null));
        }
    }

    @Nested
    @DisplayName("Serving translations")
    class Serving
    {
        private final Language english = LanguageManagerTest.this.languages.resolve("en");
        private final Language fixture = LanguageManagerTest.this.languages.resolve("zz");

        @Test
        @DisplayName("a top-level key resolves")
        void resolvesTopLevelKey()
        {
            assertEquals("Yes", LanguageManagerTest.this.languages.get(this.english, "general", "yes"));
            assertEquals("Cancel", LanguageManagerTest.this.languages.get(this.english, "general", "cancel"));
        }

        @Test
        @DisplayName("each language serves its own text")
        void eachLanguageServesItsOwn()
        {
            assertEquals("Aye", LanguageManagerTest.this.languages.get(this.fixture, "general", "yes"));
            assertEquals("Yes", LanguageManagerTest.this.languages.get(this.english, "general", "yes"));
        }

        @Test
        @DisplayName("a key missing from a translation falls back to the default language")
        void fallsBackPerKey()
        {
            // The fixture translates two keys; everything else must come from English rather than
            // showing a raw key to a user who chose that language.
            assertEquals(LanguageManagerTest.this.languages.get(this.english, "general", "dismiss"),
                    LanguageManagerTest.this.languages.get(this.fixture, "general", "dismiss"));
        }

        @Test
        @DisplayName("a nested key resolves through its dotted path")
        void resolvesNestedKey()
        {
            assertEquals("An error occurred. Please try again later.",
                    LanguageManagerTest.this.languages.get(this.english, "general", "error.occurred"));
            assertEquals("Passive", LanguageManagerTest.this.languages.get(this.english, "configuration",
                    "scanning.behavior_passive"));
        }

        @Test
        @DisplayName("a missing key returns its own dotted name rather than nothing")
        void missingKeyReturnsItsName()
        {
            assertEquals("general.no_such_key",
                    LanguageManagerTest.this.languages.get(this.english, "general", "no_such_key"));
        }

        @Test
        @DisplayName("a null language is served the default")
        void nullLanguageIsServedTheDefault()
        {
            assertEquals("Yes", LanguageManagerTest.this.languages.get(null, "general", "yes"));
        }

        @Test
        @DisplayName("positional placeholders are substituted in order")
        void substitutesInOrder()
        {
            assertEquals("Current language: English 🇺🇸",
                    LanguageManagerTest.this.languages.get(this.english, "general", "language.current",
                            "English", "🇺🇸"));
        }

        @Test
        @DisplayName("the raw template keeps its placeholders")
        void rawKeepsPlaceholders()
        {
            String raw = LanguageManagerTest.this.languages.get(this.english, "general", "language.current");

            assertTrue(raw.contains("{0}"), raw);
            assertNotEquals(raw, LanguageManagerTest.this.languages.get(this.english, "general",
                    "language.current", "English", "flag"));
        }
    }

    @Nested
    @DisplayName("Describing a language")
    class Describing
    {
        @Test
        @DisplayName("the button label is the emoji then the name")
        void buildsButtonLabel()
        {
            assertEquals("🧪 Zzyzx", LanguageManagerTest.this.languages.resolve("zz").label());
        }

        @Test
        @DisplayName("a language that could not be shown is refused")
        void refusesUnusableLanguage()
        {
            assertThrows(NullPointerException.class, () -> new Language(null, "Name", "x"));
            assertThrows(NullPointerException.class, () -> new Language("xx", null, "x"));
            assertThrows(NullPointerException.class, () -> new Language("xx", "Name", null));
            assertThrows(IllegalArgumentException.class, () -> new Language("  ", "Name", "x"));
            assertThrows(IllegalArgumentException.class, () -> new Language("xx", "  ", "x"));
        }

        @Test
        @DisplayName("a code is matched case-insensitively and by region")
        void matchesCodes()
        {
            Language english = LanguageManagerTest.this.languages.resolve("en");

            assertTrue(english.matches("en"));
            assertTrue(english.matches("EN"));
            assertTrue(english.matches("en-US"));
            assertFalse(english.matches("de"));
            assertFalse(english.matches(null));
        }
    }
}
