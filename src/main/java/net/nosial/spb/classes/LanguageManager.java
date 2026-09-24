package net.nosial.spb.classes;

import net.nosial.spb.objects.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Serves every user-facing string, translated, from the language files on the classpath.
 *
 * <p>Nothing the bot says to a user is written in Java, and nothing in Java knows which languages
 * exist. At start-up this scans {@code languages/} for {@code <code>.yml} files, reads each one's
 * {@code localization_properties} section to learn what the language calls itself, and serves
 * whatever it found. Adding a translation is adding one file; the settings menu, the language
 * picker and this class all follow from it with no code change.
 *
 * <p>A file is a nested YAML mapping flattened into dotted keys: the first segment is a section
 * ({@code general}, {@code configuration}, ...) and nested mappings join with dots, so
 * {@code configuration.main_menu.enabled_header} addresses a leaf at any depth. Templates carry
 * positional placeholders — {@code {0}}, {@code {1}} — substituted at call time.
 *
 * <p>A key missing from the requested language falls back to the default language, and a key
 * missing everywhere returns its own dotted name, so a gap shows up as a visible key rather than
 * an empty message. Files are read once at construction; the instance is immutable afterwards and
 * safe to share between worker threads.
 */
public final class LanguageManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageManager.class);

    /** The classpath directory translation files are discovered in. */
    private static final String RESOURCE_DIRECTORY = "languages";
    /** File extension every translation file carries. */
    private static final String RESOURCE_SUFFIX = ".yml";
    /** Section every translation file must declare to say what the language calls itself. */
    private static final String PROPERTIES_SECTION = "localization_properties";
    /** Key inside that section holding the language's own name. */
    private static final String PROPERTY_NAME = "name";
    /** Key inside that section holding the emoji shown beside the name. */
    private static final String PROPERTY_EMOJI = "emoji";

    private final Language defaultLanguage;
    private final List<Language> available;
    private final Map<String, Language> byCode;
    private final Map<String, Map<String, String>> messages;

    /**
     * Discovers and loads every translation file on the classpath.
     *
     * @param defaultLanguageCode the ISO 639-1 code used when a key or a language is missing,
     *                            typically {@code bot.default_language} from the configuration
     * @throws IllegalStateException If no translation file was found, or the default language has
     *                               none, since the bot would then have nothing to say
     */
    public LanguageManager(String defaultLanguageCode)
    {
        Map<String, Map<String, String>> loadedMessages = new LinkedHashMap<>();
        Map<String, Language> loadedLanguages = new LinkedHashMap<>();

        for (String code : discoverLanguageCodes())
        {
            load(code, loadedLanguages, loadedMessages);
        }

        if (loadedLanguages.isEmpty())
        {
            throw new IllegalStateException("No translation files found on the classpath under '" + RESOURCE_DIRECTORY + "/'; the bot would have nothing to say");
        }

        this.messages = Map.copyOf(loadedMessages);
        this.byCode = Map.copyOf(loadedLanguages);

        List<Language> sorted = new ArrayList<>(loadedLanguages.values());
        sorted.sort(Comparator.comparing(Language::code));
        this.available = List.copyOf(sorted);

        Language resolved = resolve(defaultLanguageCode);
        if (resolved == null)
        {
            throw new IllegalStateException("No translation file for the default language '"
                    + defaultLanguageCode + "'; found " + this.byCode.keySet());
        }

        this.defaultLanguage = resolved;
        LOGGER.info("Loaded {} language(s) {} (default: {})", this.available.size(),
                this.available.stream().map(Language::code).toList(), this.defaultLanguage.code());
    }

    /**
     * Returns the language used when a key or a requested language is missing.
     *
     * @return the default language
     */
    public Language defaultLanguage()
    {
        return this.defaultLanguage;
    }

    /**
     * Returns every language that has a translation file, ordered by code.
     *
     * <p>This is what the settings menu offers; there is no other list of languages anywhere.
     *
     * @return the available languages, always containing the default
     */
    public List<Language> availableLanguages()
    {
        return this.available;
    }

    /**
     * Returns the language for a code, if one was found.
     *
     * <p>Accepts a regional variant, so a client reporting {@code pt-BR} resolves to {@code pt}
     * when that file exists.
     *
     * @param languageCode the ISO 639-1 code, possibly with a region suffix, or {@code null}
     * @return the language, or {@code null} when nothing matches
     */
    public Language resolve(String languageCode)
    {
        if (languageCode == null || languageCode.isBlank())
        {
            return null;
        }

        Language exact = this.byCode.get(languageCode.strip().toLowerCase());
        if (exact != null)
        {
            return exact;
        }

        for (Language language : this.available)
        {
            if (language.matches(languageCode))
            {
                return language;
            }
        }

        return null;
    }

    /**
     * Returns the language for a code, falling back to the default.
     *
     * @param languageCode the ISO 639-1 code, possibly with a region suffix, or {@code null}
     * @return the matching language, or the default when nothing matches
     */
    public Language resolveOrDefault(String languageCode)
    {
        Language resolved = resolve(languageCode);
        return resolved != null ? resolved : this.defaultLanguage;
    }

    /**
     * Returns whether a language has a translation file.
     *
     * @param language the language to check
     * @return {@code true} when the language can be served
     */
    public boolean isAvailable(Language language)
    {
        return language != null && this.byCode.containsKey(language.code());
    }

    /**
     * Returns a translated string with its placeholders filled in.
     *
     * @param language the language to translate into, or {@code null} for the default
     * @param section the top-level section (e.g. {@code "general"})
     * @param key the key within the section, nested levels joined with dots (e.g.
     *            {@code "main_menu.enabled_header"})
     * @param args the values substituted for {@code {0}}, {@code {1}}, ... in order
     * @return the translated string, or the dotted key when no translation exists
     */
    public String get(Language language, String section, String key, Object... args)
    {
        return format(lookup(language, section, key), args);
    }

    /**
     * Returns a translated string without substituting placeholders.
     *
     * @param language the language to translate into, or {@code null} for the default
     * @param section the top-level section
     * @param key the key within the section, nested levels joined with dots
     * @return the raw template, or the dotted key when no translation exists
     */
    public String get(Language language, String section, String key)
    {
        return lookup(language, section, key);
    }

    /**
     * Returns the number of translation keys loaded for a language.
     *
     * @param language the language to measure
     * @return the key count, or zero when the language has no file
     */
    public int keyCount(Language language)
    {
        Map<String, String> loaded = language != null ? this.messages.get(language.code()) : null;
        return loaded != null ? loaded.size() : 0;
    }

    /**
     * Finds a template, falling back to the default language and then to the key itself.
     *
     * @param language the requested language, or {@code null} for the default
     * @param section the top-level section
     * @param key the key within the section
     * @return the template, or the dotted key when nothing matched
     */
    private String lookup(Language language, String section, String key)
    {
        String dotted = section + "." + key;
        Language requested = language != null ? language : this.defaultLanguage;

        Map<String, String> translations = this.messages.get(requested.code());
        if (translations != null)
        {
            String value = translations.get(dotted);
            if (value != null)
            {
                return value;
            }
        }

        if (!requested.equals(this.defaultLanguage))
        {
            String value = this.messages.get(this.defaultLanguage.code()).get(dotted);
            if (value != null)
            {
                LOGGER.debug("Key '{}' is missing from '{}', serving '{}'", dotted, requested.code(),
                        this.defaultLanguage.code());
                return value;
            }
        }

        LOGGER.warn("Missing translation key '{}'", dotted);
        return dotted;
    }

    /**
     * Substitutes positional placeholders into a template.
     *
     * @param template the template, possibly {@code null}
     * @param args the values to substitute in order
     * @return the filled-in string
     */
    private static String format(String template, Object[] args)
    {
        if (template == null || args == null || args.length == 0)
        {
            return template;
        }

        String result = template;
        for (int i = 0; i < args.length; i++)
        {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }

        return result;
    }

    /**
     * Reads one translation file into the language and message maps being built.
     *
     * <p>An unusable file — missing, malformed, or not declaring what language it is — is logged
     * and skipped rather than failing start-up, so one bad translation cannot stop the bot.
     *
     * @param code the ISO 639-1 code taken from the file name
     * @param languages the languages found so far, added to when this file is usable
     * @param messages the translations found so far, added to when this file is usable
     */
    private static void load(String code, Map<String, Language> languages,
                             Map<String, Map<String, String>> messages)
    {
        String resource = RESOURCE_DIRECTORY + "/" + code + RESOURCE_SUFFIX;

        try (InputStream in = classLoader().getResourceAsStream(resource))
        {
            if (in == null)
            {
                return;
            }

            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);

            Object parsed = new Yaml(options).load(in);
            if (!(parsed instanceof Map))
            {
                LOGGER.warn("Translation file '{}' is not a YAML mapping and was ignored", resource);
                return;
            }

            Map<String, String> flat = new HashMap<>();
            flatten(null, parsed, flat);

            String name = flat.get(PROPERTIES_SECTION + "." + PROPERTY_NAME);
            String emoji = flat.get(PROPERTIES_SECTION + "." + PROPERTY_EMOJI);
            if (name == null || name.isBlank() || emoji == null || emoji.isBlank())
            {
                LOGGER.warn("Translation file '{}' has no usable '{}' section ('{}' and '{}' are required) "
                                + "and was ignored", resource, PROPERTIES_SECTION, PROPERTY_NAME, PROPERTY_EMOJI);
                return;
            }

            LOGGER.debug("Loaded {} translation key(s) for '{}' ({} {})", flat.size(), code, emoji, name);
            languages.put(code, new Language(code, name, emoji));
            messages.put(code, Map.copyOf(flat));
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Failed to load translation file '{}': {}", resource, e.getMessage());
        }
    }

    /**
     * Returns the codes of every translation file on the classpath, in alphabetical order.
     *
     * <p>Supports both an exploded directory and a directory inside a jar, so the shaded
     * distribution discovers the same files the development build does.
     *
     * @return the discovered language codes
     */
    private static Iterable<String> discoverLanguageCodes()
    {
        TreeSet<String> codes = new TreeSet<>();

        try
        {
            Enumeration<URL> roots = classLoader().getResources(RESOURCE_DIRECTORY);
            while (roots.hasMoreElements())
            {
                codes.addAll(listCodes(roots.nextElement()));
            }
        }
        catch (IOException e)
        {
            LOGGER.warn("Failed to scan '{}' for translation files: {}", RESOURCE_DIRECTORY, e.getMessage());
        }

        return codes;
    }

    /**
     * Lists the language codes under one classpath root.
     *
     * @param root the classpath directory URL
     * @return the codes found there
     * @throws IOException If the directory or jar cannot be read
     */
    private static List<String> listCodes(URL root) throws IOException
    {
        List<String> codes = new ArrayList<>();

        if ("file".equals(root.getProtocol()))
        {
            try (Stream<Path> files = Files.list(Path.of(root.toURI())))
            {
                for (Path file : files.toList())
                {
                    String name = file.getFileName().toString();
                    if (Files.isRegularFile(file) && name.endsWith(RESOURCE_SUFFIX))
                    {
                        codes.add(name.substring(0, name.length() - RESOURCE_SUFFIX.length()));
                    }
                }
            }
            catch (URISyntaxException e)
            {
                throw new IOException("Invalid translation directory URI " + root, e);
            }

            return codes;
        }

        if ("jar".equals(root.getProtocol()))
        {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            String prefix = RESOURCE_DIRECTORY + "/";

            try (var jar = connection.getJarFile())
            {
                for (var entry : jar.stream().filter(entry -> !entry.isDirectory()).toList())
                {
                    String name = entry.getName();
                    if (name.startsWith(prefix) && name.endsWith(RESOURCE_SUFFIX))
                    {
                        String code = name.substring(prefix.length(), name.length() - RESOURCE_SUFFIX.length());
                        if (!code.contains("/"))
                        {
                            codes.add(code);
                        }
                    }
                }
            }

            return codes;
        }

        LOGGER.warn("Skipping translation directory with unsupported protocol '{}' at {}", root.getProtocol(), root);
        return Collections.emptyList();
    }

    /**
     * Flattens a nested YAML mapping into dotted keys, keeping only scalar leaves.
     *
     * @param prefix the dotted path so far, or {@code null} at the root
     * @param node the node to flatten
     * @param flat the destination map
     */
    private static void flatten(String prefix, Object node, Map<String, String> flat)
    {
        if (node instanceof Map<?, ?> map)
        {
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                String key = String.valueOf(entry.getKey());
                flatten(prefix == null ? key : prefix + "." + key, entry.getValue(), flat);
            }
        }
        else if (node != null && prefix != null)
        {
            flat.put(prefix, String.valueOf(node));
        }
    }

    /**
     * Returns the class loader translation files are resolved against.
     *
     * @return the context class loader when set, otherwise this class's loader
     */
    private static ClassLoader classLoader()
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LanguageManager.class.getClassLoader();
    }
}
