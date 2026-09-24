package net.nosial.spb.classes;

import net.nosial.spb.exceptions.ConfigurationException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The process configuration, loaded and validated from a YAML file.
 *
 * <p>Constructing an instance reads the file with SnakeYAML's safe loader and validates it field by
 * field; every failure is reported as a {@link ConfigurationException} naming the offending section
 * and key so operators get immediate, actionable feedback instead of silent misconfiguration. Once
 * constructed, the instance is immutable and is treated as read-only for the lifetime of the
 * process.
 *
 * <p>The {@code bot} section is required. The {@code federation} section is optional; when it is
 * absent {@link #hasFederation()} returns {@code false} and the bot runs without Federation
 * support.
 */
public class Configuration
{
    private static final String DEFAULT_API_SCHEME = "https";
    private static final String DEFAULT_API_HOST = "api.telegram.org";
    private static final int DEFAULT_API_PORT = 443;
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final int DEFAULT_NOTIFICATION_INTERVAL_SECONDS = 60;
    private static final String DEFAULT_LANGUAGE = "en";
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Path path;

    private final String name;
    private final String apiKey;
    private final String apiScheme;
    private final String apiHost;
    private final int apiPort;
    private final boolean testServer;
    private final int workerThreads;
    private final int queueCapacity;
    private final int notificationIntervalSeconds;
    private final boolean privacyMode;
    private final String defaultLanguage;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int writeTimeoutSeconds;
    private final int shutdownTimeoutSeconds;

    private final String federationEndpoint;
    private final String federationAccessToken;

    /**
     * Loads and validates the configuration from the YAML file at the given path.
     *
     * @param filePath the configuration file path
     * @throws ConfigurationException If the path is not a valid file path, or the file is missing,
     *                                unreadable, malformed, or invalid
     */
    public Configuration(String filePath) throws ConfigurationException
    {
        this(toPath(filePath));
    }

    /**
     * Loads and validates the configuration from the YAML file at the given path.
     *
     * @param filePath the configuration file path
     * @throws ConfigurationException If the file is missing, unreadable, malformed, or invalid
     */
    public Configuration(Path filePath) throws ConfigurationException
    {
        this.path = Objects.requireNonNull(filePath, "filePath must not be null");

        Map<String, Object> root = read(this.path);
        Map<String, Object> bot = getMap(root, "bot", false);
        Map<String, Object> federation = getMap(root, "federation", true);

        this.name = requireText(getString(bot, "name", false), "bot.name");
        this.apiKey = requireText(getString(bot, "api_key", false), "bot.api_key");
        this.apiScheme = orDefault(getString(bot, "api_scheme", true), DEFAULT_API_SCHEME);
        this.apiHost = orDefault(getString(bot, "api_host", true), DEFAULT_API_HOST);
        this.apiPort = orDefault(getInteger(bot, "api_port", true), DEFAULT_API_PORT);
        this.testServer = orDefault(getBoolean(bot, "test_server", true), false);
        this.workerThreads = orDefault(getInteger(bot, "worker_threads", true), defaultWorkerThreads());
        this.queueCapacity = orDefault(getInteger(bot, "queue_capacity", true), DEFAULT_QUEUE_CAPACITY);
        this.notificationIntervalSeconds = orDefault(getInteger(bot, "notification_interval_seconds", true), DEFAULT_NOTIFICATION_INTERVAL_SECONDS);
        this.privacyMode = orDefault(getBoolean(bot, "privacy_mode", true), false);
        this.defaultLanguage = orDefault(getString(bot, "default_language", true), DEFAULT_LANGUAGE);
        this.connectTimeoutSeconds = orDefault(getInteger(bot, "connect_timeout_seconds", true), DEFAULT_CONNECT_TIMEOUT_SECONDS);
        this.readTimeoutSeconds = orDefault(getInteger(bot, "read_timeout_seconds", true), DEFAULT_READ_TIMEOUT_SECONDS);
        this.writeTimeoutSeconds = orDefault(getInteger(bot, "write_timeout_seconds", true), DEFAULT_WRITE_TIMEOUT_SECONDS);
        this.shutdownTimeoutSeconds = orDefault(getInteger(bot, "shutdown_timeout_seconds", true), DEFAULT_SHUTDOWN_TIMEOUT_SECONDS);

        if (federation == null)
        {
            this.federationEndpoint = null;
            this.federationAccessToken = null;
        }
        else
        {
            this.federationEndpoint = requireText(getString(federation, "endpoint", false), "federation.endpoint");
            this.federationAccessToken = getString(federation, "access_token", true);
        }

        validate();
    }

    /**
     * Returns the path of the file this configuration was loaded from.
     *
     * @return the configuration file path
     */
    public Path getPath()
    {
        return this.path;
    }

    /**
     * Returns the display name of the bot, used in the {@code /start} reply.
     *
     * @return the bot name
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * Returns the Telegram bot API key (token) issued by BotFather.
     *
     * @return the bot API key
     */
    public String getApiKey()
    {
        return this.apiKey;
    }

    /**
     * Returns the URL scheme used to reach the Telegram API host.
     *
     * @return the scheme, {@code https} by default
     */
    public String getApiScheme()
    {
        return this.apiScheme;
    }

    /**
     * Returns the Telegram API host.
     *
     * @return the host, {@code api.telegram.org} by default
     */
    public String getApiHost()
    {
        return this.apiHost;
    }

    /**
     * Returns the port the Telegram API host listens on.
     *
     * @return the port, {@code 443} by default
     */
    public int getApiPort()
    {
        return this.apiPort;
    }

    /**
     * Returns whether the bot talks to the Telegram test server rather than the production one.
     *
     * @return {@code true} when the test server is used, {@code false} by default
     */
    public boolean isTestServer()
    {
        return this.testServer;
    }

    /**
     * Returns the number of concurrent threads processing incoming updates.
     *
     * @return the worker thread count, derived from the available processors by default
     */
    public int getWorkerThreads()
    {
        return this.workerThreads;
    }

    /**
     * Returns the maximum number of pending updates before surplus updates are dropped.
     *
     * @return the queue capacity, {@code 1024} by default
     */
    public int getQueueCapacity()
    {
        return this.queueCapacity;
    }

    /**
     * Returns the interval between operator report notification polls.
     *
     * @return the interval in seconds, {@code 60} by default
     */
    public int getNotificationIntervalSeconds()
    {
        return this.notificationIntervalSeconds;
    }

    /**
     * Returns the default privacy setting for newly registered chats; when enabled, optional
     * identity and metadata fields are omitted from Federation requests.
     *
     * @return {@code true} when privacy mode is enabled by default, {@code false} by default
     */
    public boolean isPrivacyMode()
    {
        return this.privacyMode;
    }

    /**
     * Returns the default language code for user-facing text.
     *
     * @return the language code, {@code en} by default
     */
    public String getDefaultLanguage()
    {
        return this.defaultLanguage;
    }

    /**
     * Returns how long the Telegram API client waits to establish a connection.
     *
     * @return the connect timeout in seconds, {@code 30} by default
     */
    public int getConnectTimeoutSeconds()
    {
        return this.connectTimeoutSeconds;
    }

    /**
     * Returns how long the Telegram API client waits for a response before failing.
     *
     * <p>This must comfortably outlast a long poll, which deliberately holds the connection open
     * until an update arrives or Telegram's own long-poll timeout expires, so the default is
     * generous rather than tight.
     *
     * @return the read timeout in seconds, {@code 300} by default
     */
    public int getReadTimeoutSeconds()
    {
        return this.readTimeoutSeconds;
    }

    /**
     * Returns how long the Telegram API client waits while sending a request body.
     *
     * @return the write timeout in seconds, {@code 60} by default
     */
    public int getWriteTimeoutSeconds()
    {
        return this.writeTimeoutSeconds;
    }

    /**
     * Returns how long shutdown waits for in-flight updates to finish before the queue is
     * abandoned.
     *
     * @return the shutdown timeout in seconds, {@code 30} by default
     */
    public int getShutdownTimeoutSeconds()
    {
        return this.shutdownTimeoutSeconds;
    }

    /**
     * Returns whether the optional {@code federation} section is present, meaning the bot runs with
     * Federation support.
     *
     * @return {@code true} when a Federation endpoint is configured
     */
    public boolean hasFederation()
    {
        return this.federationEndpoint != null;
    }

    /**
     * Returns the base URL of the Federation server.
     *
     * @return the endpoint URL, or {@code null} when the {@code federation} section is absent
     */
    public String getFederationEndpoint()
    {
        return this.federationEndpoint;
    }

    /**
     * Returns the access token used to authenticate against the Federation server.
     *
     * @return the access token, or {@code null} for anonymous access or when the
     *         {@code federation} section is absent
     */
    public String getFederationAccessToken()
    {
        return this.federationAccessToken;
    }

    /**
     * Converts a file path string into a {@link Path}.
     *
     * @param filePath the configuration file path
     * @return the corresponding path
     * @throws ConfigurationException If the string is not a valid file path
     */
    private static Path toPath(String filePath) throws ConfigurationException
    {
        Objects.requireNonNull(filePath, "filePath must not be null");

        try
        {
            return Path.of(filePath);
        }
        catch (InvalidPathException e)
        {
            throw new ConfigurationException("Invalid configuration file path: " + filePath, e);
        }
    }

    /**
     * Reads and parses the YAML file at the given path into its root mapping.
     *
     * @param path the configuration file path
     * @return the root YAML mapping
     * @throws ConfigurationException If the file is missing, unreadable, empty, or malformed
     */
    private static Map<String, Object> read(Path path) throws ConfigurationException
    {
        if (!Files.exists(path))
        {
            throw new ConfigurationException("Configuration file does not exist: " + path);
        }

        if (!Files.isReadable(path))
        {
            throw new ConfigurationException("Configuration file is not readable: " + path);
        }

        try (InputStream in = Files.newInputStream(path))
        {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);

            Object parsed = new Yaml(options).load(in);
            if (parsed == null)
            {
                throw new ConfigurationException("Configuration file is empty: " + path);
            }

            if (!(parsed instanceof Map))
            {
                throw new ConfigurationException("Configuration file must contain a YAML mapping at the root: " + path);
            }

            return castStringMap(parsed, "root");
        }
        catch (IOException e)
        {
            throw new ConfigurationException("Failed to read configuration file " + path + ": " + e.getMessage(), e);
        }
        catch (YAMLException e)
        {
            throw new ConfigurationException("Malformed YAML in configuration file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates every field that carries a constraint beyond its type.
     *
     * @throws ConfigurationException If any value fails validation
     */
    private void validate() throws ConfigurationException
    {
        if (!this.apiScheme.equalsIgnoreCase("https") && !this.apiScheme.equalsIgnoreCase("http"))
        {
            throw new ConfigurationException("Field 'bot.api_scheme' must be 'https' or 'http', got " + this.apiScheme);
        }

        if (this.apiPort < 1 || this.apiPort > 65535)
        {
            throw new ConfigurationException("Field 'bot.api_port' must be in [1, 65535], got " + this.apiPort);
        }

        if (this.workerThreads < 1)
        {
            throw new ConfigurationException("Field 'bot.worker_threads' must be >= 1, got " + this.workerThreads);
        }

        if (this.queueCapacity < 1)
        {
            throw new ConfigurationException("Field 'bot.queue_capacity' must be >= 1, got " + this.queueCapacity);
        }

        if (this.notificationIntervalSeconds < 1)
        {
            throw new ConfigurationException("Field 'bot.notification_interval_seconds' must be >= 1, got " + this.notificationIntervalSeconds);
        }

        if (this.connectTimeoutSeconds < 1)
        {
            throw new ConfigurationException("Field 'bot.connect_timeout_seconds' must be >= 1, got " + this.connectTimeoutSeconds);
        }

        if (this.readTimeoutSeconds < 1)
        {
            throw new ConfigurationException("Field 'bot.read_timeout_seconds' must be >= 1, got " + this.readTimeoutSeconds);
        }

        if (this.writeTimeoutSeconds < 1)
        {
            throw new ConfigurationException("Field 'bot.write_timeout_seconds' must be >= 1, got " + this.writeTimeoutSeconds);
        }

        if (this.shutdownTimeoutSeconds < 1)
        {
            throw new ConfigurationException("Field 'bot.shutdown_timeout_seconds' must be >= 1, got " + this.shutdownTimeoutSeconds);
        }

        if (this.federationEndpoint != null)
        {
            validateEndpoint(this.federationEndpoint);
        }

        if (this.federationAccessToken != null && this.federationAccessToken.chars().anyMatch(Character::isWhitespace))
        {
            throw new ConfigurationException("Field 'federation.access_token' must not contain whitespace");
        }
    }

    /**
     * Verifies that the Federation endpoint is a valid URL with a host, mirroring the validation
     * performed by the {@link net.nosial.jfederation.FederationClient} constructor so
     * misconfiguration is caught at load time.
     *
     * @param endpoint the endpoint URL
     * @throws ConfigurationException If the endpoint is not a valid URL or lacks a host
     */
    private static void validateEndpoint(String endpoint) throws ConfigurationException
    {
        URI uri;
        try
        {
            uri = new URI(endpoint);
        }
        catch (URISyntaxException e)
        {
            throw new ConfigurationException("Field 'federation.endpoint' must be a valid URL, got " + endpoint, e);
        }

        if (uri.getHost() == null || uri.getHost().isEmpty())
        {
            throw new ConfigurationException("Field 'federation.endpoint' must have a valid host, got " + endpoint);
        }
    }

    /**
     * Returns the worker thread count used when {@code bot.worker_threads} is not configured.
     *
     * @return the default worker thread count
     */
    private static int defaultWorkerThreads()
    {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Rejects a required value that is blank.
     *
     * @param value the value to check
     * @param field the fully-qualified field name, used in error messages
     * @return the value
     * @throws ConfigurationException If the value is blank
     */
    private static String requireText(String value, String field) throws ConfigurationException
    {
        if (value == null || value.isBlank())
        {
            throw new ConfigurationException("Field '" + field + "' must not be blank");
        }

        return value;
    }

    /**
     * Returns the value, or the default when the value is absent.
     *
     * @param value the configured value, or {@code null} when absent
     * @param fallback the default to apply when the value is absent
     * @param <T> the value type
     * @return the value, or the default
     */
    private static <T> T orDefault(T value, T fallback)
    {
        return value != null ? value : fallback;
    }

    /**
     * Extracts a required or optional string field from a YAML mapping.
     *
     * @param section the section mapping containing the field
     * @param key the field key
     * @param optional whether the field is optional
     * @return the field value, or {@code null} when optional and absent
     * @throws ConfigurationException If the field is missing while required, or is not a string
     */
    private static String getString(Map<String, Object> section, String key, boolean optional)
            throws ConfigurationException
    {
        Object value = section.get(key);
        if (value == null)
        {
            if (optional)
            {
                return null;
            }
            throw new ConfigurationException("Missing required field '" + key + "'");
        }

        if (!(value instanceof String string))
        {
            throw new ConfigurationException("Field '" + key + "' must be a string, got " + typeName(value));
        }

        if (string.isEmpty())
        {
            if (optional)
            {
                return null;
            }
            throw new ConfigurationException("Field '" + key + "' must not be empty");
        }

        return string;
    }

    /**
     * Extracts a required or optional integer field from a YAML mapping.
     *
     * @param section the section mapping containing the field
     * @param key the field key
     * @param optional whether the field is optional
     * @return the field value, or {@code null} when optional and absent
     * @throws ConfigurationException If the field is missing while required, or is not an integer
     */
    private static Integer getInteger(Map<String, Object> section, String key, boolean optional)
            throws ConfigurationException
    {
        Object value = section.get(key);
        if (value == null)
        {
            if (optional)
            {
                return null;
            }
            throw new ConfigurationException("Missing required field '" + key + "'");
        }

        if (!(value instanceof Integer integer))
        {
            throw new ConfigurationException("Field '" + key + "' must be an integer, got " + typeName(value));
        }

        return integer;
    }

    /**
     * Extracts a required or optional boolean field from a YAML mapping.
     *
     * @param section the section mapping containing the field
     * @param key the field key
     * @param optional whether the field is optional
     * @return the field value, or {@code null} when optional and absent
     * @throws ConfigurationException If the field is missing while required, or is not a boolean
     */
    private static Boolean getBoolean(Map<String, Object> section, String key, boolean optional)
            throws ConfigurationException
    {
        Object value = section.get(key);
        if (value == null)
        {
            if (optional)
            {
                return null;
            }
            throw new ConfigurationException("Missing required field '" + key + "'");
        }

        if (!(value instanceof Boolean bool))
        {
            throw new ConfigurationException("Field '" + key + "' must be a boolean, got " + typeName(value));
        }

        return bool;
    }

    /**
     * Extracts a nested mapping from a YAML mapping.
     *
     * @param section the section mapping containing the nested section
     * @param key the nested section key
     * @param optional whether the nested section is optional
     * @return the nested mapping, or {@code null} when optional and absent
     * @throws ConfigurationException If the nested section is missing while required, or is not a
     *                                mapping
     */
    private static Map<String, Object> getMap(Map<String, Object> section, String key, boolean optional)
            throws ConfigurationException
    {
        Object value = section.get(key);
        if (value == null)
        {
            if (optional)
            {
                return null;
            }
            throw new ConfigurationException("Missing required section '" + key + "'");
        }

        return castStringMap(value, key);
    }

    /**
     * Casts a parsed YAML object to a string-keyed mapping, rejecting non-string keys.
     *
     * @param value the parsed YAML object
     * @param name the section name, used in error messages
     * @return the mapping
     * @throws ConfigurationException If the object is not a mapping or contains non-string keys
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Object value, String name) throws ConfigurationException
    {
        if (!(value instanceof Map))
        {
            throw new ConfigurationException("Section '" + name + "' must be a mapping, got " + typeName(value));
        }

        for (Object key : ((Map<Object, Object>) value).keySet())
        {
            if (!(key instanceof String))
            {
                throw new ConfigurationException("Section '" + name + "' must only contain string keys, got "
                        + typeName(key));
            }
        }

        return (Map<String, Object>) value;
    }

    /**
     * Returns a human-readable type name for error messages.
     *
     * @param value the value to describe
     * @return a human-readable type name
     */
    private static String typeName(Object value)
    {
        if (value == null)
        {
            return "null";
        }
        if (value instanceof Map)
        {
            return "a mapping";
        }
        if (value instanceof List)
        {
            return "a list";
        }
        return "a " + value.getClass().getSimpleName();
    }

    @Override
    public String toString()
    {
        return "Configuration{path=" + this.path
                + ", name=" + this.name
                + ", apiScheme=" + this.apiScheme
                + ", apiHost=" + this.apiHost
                + ", apiPort=" + this.apiPort
                + ", testServer=" + this.testServer
                + ", workerThreads=" + this.workerThreads
                + ", queueCapacity=" + this.queueCapacity
                + ", notificationIntervalSeconds=" + this.notificationIntervalSeconds
                + ", privacyMode=" + this.privacyMode
                + ", defaultLanguage=" + this.defaultLanguage
                + ", connectTimeoutSeconds=" + this.connectTimeoutSeconds
                + ", readTimeoutSeconds=" + this.readTimeoutSeconds
                + ", writeTimeoutSeconds=" + this.writeTimeoutSeconds
                + ", shutdownTimeoutSeconds=" + this.shutdownTimeoutSeconds
                + ", federation=" + (hasFederation() ? this.federationEndpoint : "disabled")
                + "}";
    }
}
