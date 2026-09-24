package net.nosial.spb.classes;

import net.nosial.spb.exceptions.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for loading, defaulting, and validating {@link Configuration} files.
 */
class ConfigurationTest
{
    @TempDir
    Path directory;

    /**
     * Writes the given YAML content to a file in the temporary directory.
     *
     * @param content the YAML document
     * @return the path of the written file
     * @throws IOException If the file cannot be written
     */
    private Path write(String content) throws IOException
    {
        Path path = this.directory.resolve("config.yaml");
        Files.writeString(path, content);
        return path;
    }

    /**
     * Loads a configuration from the given YAML content.
     *
     * @param content the YAML document
     * @return the loaded configuration
     * @throws Exception If the file cannot be written or the configuration is invalid
     */
    private Configuration load(String content) throws Exception
    {
        return new Configuration(write(content));
    }

    /**
     * Asserts that loading the given YAML content fails, and returns the resulting exception.
     *
     * @param content the YAML document
     * @return the thrown exception
     * @throws IOException If the file cannot be written
     */
    private ConfigurationException loadFailure(String content) throws IOException
    {
        Path path = write(content);
        return assertThrows(ConfigurationException.class, () -> new Configuration(path));
    }

    @Nested
    @DisplayName("Loading valid configurations")
    class ValidConfigurations
    {
        @Test
        @DisplayName("every field is read from a fully-populated file")
        void loadsEveryField() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      api_host: telegram.example.com
                      api_port: 8443
                      api_scheme: http
                      test_server: true
                      worker_threads: 8
                      queue_capacity: 2048
                      notification_interval_seconds: 30
                      default_language: es
                      privacy_mode: true
                      connect_timeout_seconds: 15
                      read_timeout_seconds: 120
                      write_timeout_seconds: 45
                      shutdown_timeout_seconds: 10
                    federation:
                      endpoint: "http://127.0.0.1:7000/"
                      access_token: "abcdefghijklmnopqrstuvwxyz123456"
                    """);

            assertEquals("SpamProtectionBot", configuration.getName());
            assertEquals("123456:ABCDEF", configuration.getApiKey());
            assertEquals("telegram.example.com", configuration.getApiHost());
            assertEquals(8443, configuration.getApiPort());
            assertEquals("http", configuration.getApiScheme());
            assertTrue(configuration.isTestServer());
            assertEquals(8, configuration.getWorkerThreads());
            assertEquals(2048, configuration.getQueueCapacity());
            assertEquals(30, configuration.getNotificationIntervalSeconds());
            assertEquals("es", configuration.getDefaultLanguage());
            assertTrue(configuration.isPrivacyMode());
            assertEquals(15, configuration.getConnectTimeoutSeconds());
            assertEquals(120, configuration.getReadTimeoutSeconds());
            assertEquals(45, configuration.getWriteTimeoutSeconds());
            assertEquals(10, configuration.getShutdownTimeoutSeconds());
            assertTrue(configuration.hasFederation());
            assertEquals("http://127.0.0.1:7000/", configuration.getFederationEndpoint());
            assertEquals("abcdefghijklmnopqrstuvwxyz123456", configuration.getFederationAccessToken());
        }

        @Test
        @DisplayName("optional fields fall back to their defaults")
        void appliesDefaults() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    """);

            assertEquals("https", configuration.getApiScheme());
            assertEquals("api.telegram.org", configuration.getApiHost());
            assertEquals(443, configuration.getApiPort());
            assertFalse(configuration.isTestServer());
            assertEquals(Math.max(2, Runtime.getRuntime().availableProcessors()), configuration.getWorkerThreads());
            assertEquals(1024, configuration.getQueueCapacity());
            assertEquals(60, configuration.getNotificationIntervalSeconds());
            assertFalse(configuration.isPrivacyMode());
            assertEquals("en", configuration.getDefaultLanguage());
            assertEquals(30, configuration.getConnectTimeoutSeconds());
            assertEquals(300, configuration.getReadTimeoutSeconds());
            assertEquals(60, configuration.getWriteTimeoutSeconds());
            assertEquals(30, configuration.getShutdownTimeoutSeconds());
        }

        @Test
        @DisplayName("the federation section is optional")
        void federationIsOptional() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    """);

            assertFalse(configuration.hasFederation());
            assertNull(configuration.getFederationEndpoint());
            assertNull(configuration.getFederationAccessToken());
        }

        @Test
        @DisplayName("the federation access token is optional")
        void federationAccessTokenIsOptional() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      endpoint: "https://federation.example.com/"
                    """);

            assertTrue(configuration.hasFederation());
            assertEquals("https://federation.example.com/", configuration.getFederationEndpoint());
            assertNull(configuration.getFederationAccessToken());
        }

        @Test
        @DisplayName("the file path can be given as a string")
        void acceptsStringPath() throws Exception
        {
            Path path = write("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    """);

            Configuration configuration = new Configuration(path.toString());

            assertEquals("SpamProtectionBot", configuration.getName());
            assertEquals(path, configuration.getPath());
        }

        @Test
        @DisplayName("comments and blank lines are ignored")
        void ignoresComments() throws Exception
        {
            Configuration configuration = load("""
                    # SpamProtectionBot configuration

                    bot:
                      # required
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"  # issued by BotFather
                    """);

            assertEquals("SpamProtectionBot", configuration.getName());
        }

        @Test
        @DisplayName("the bundled example configuration loads")
        void loadsExampleConfiguration() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456789:REPLACE-WITH-YOUR-OWN-BOT-TOKEN"
                      api_host: api.telegram.org
                      api_port: 443
                      api_scheme: https
                      test_server: false
                      worker_threads: 4
                      queue_capacity: 1024
                      notification_interval_seconds: 60
                      default_language: en
                      privacy_mode: false
                      connect_timeout_seconds: 30
                      read_timeout_seconds: 300
                      write_timeout_seconds: 60
                      shutdown_timeout_seconds: 30
                    federation:
                      endpoint: "http://127.0.0.1:7000/"
                      access_token: "replace-with-your-own-access_token"
                    """);

            assertNotNull(configuration.toString());
            assertEquals(4, configuration.getWorkerThreads());
            assertTrue(configuration.hasFederation());
        }
    }

    @Nested
    @DisplayName("Rejecting unusable files")
    class UnusableFiles
    {
        @Test
        @DisplayName("a missing file is rejected")
        void rejectsMissingFile()
        {
            Path missing = ConfigurationTest.this.directory.resolve("absent.yaml");

            ConfigurationException e = assertThrows(ConfigurationException.class, () -> new Configuration(missing));
            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        }

        @Test
        @DisplayName("a directory is rejected")
        void rejectsDirectory()
        {
            assertThrows(ConfigurationException.class,
                    () -> new Configuration(ConfigurationTest.this.directory));
        }

        @Test
        @DisplayName("an empty file is rejected")
        void rejectsEmptyFile() throws IOException
        {
            ConfigurationException e = loadFailure("");
            assertTrue(e.getMessage().contains("empty"), e.getMessage());
        }

        @Test
        @DisplayName("a comment-only file is rejected")
        void rejectsCommentOnlyFile() throws IOException
        {
            ConfigurationException e = loadFailure("# nothing here\n");
            assertTrue(e.getMessage().contains("empty"), e.getMessage());
        }

        @Test
        @DisplayName("malformed YAML is rejected")
        void rejectsMalformedYaml() throws IOException
        {
            ConfigurationException e = loadFailure("bot:\n  name: [unterminated\n");
            assertTrue(e.getMessage().contains("Malformed YAML"), e.getMessage());
        }

        @Test
        @DisplayName("a scalar root is rejected")
        void rejectsScalarRoot() throws IOException
        {
            ConfigurationException e = loadFailure("just a string\n");
            assertTrue(e.getMessage().contains("mapping at the root"), e.getMessage());
        }

        @Test
        @DisplayName("duplicate keys are rejected")
        void rejectsDuplicateKeys() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      name: Impostor
                      api_key: "123456:ABCDEF"
                    """);

            assertTrue(e.getMessage().contains("Malformed YAML"), e.getMessage());
        }

        @Test
        @DisplayName("a null path is rejected")
        void rejectsNullPath()
        {
            assertThrows(NullPointerException.class, () -> new Configuration((Path) null));
            assertThrows(NullPointerException.class, () -> new Configuration((String) null));
        }
    }

    @Nested
    @DisplayName("Validating the bot section")
    class BotSection
    {
        @Test
        @DisplayName("the section is required")
        void requiresSection() throws IOException
        {
            ConfigurationException e = loadFailure("federation:\n  endpoint: \"http://127.0.0.1:7000/\"\n");
            assertTrue(e.getMessage().contains("Missing required section 'bot'"), e.getMessage());
        }

        @Test
        @DisplayName("the section must be a mapping")
        void requiresMapping() throws IOException
        {
            ConfigurationException e = loadFailure("bot: SpamProtectionBot\n");
            assertTrue(e.getMessage().contains("Section 'bot' must be a mapping"), e.getMessage());
        }

        @Test
        @DisplayName("the name is required")
        void requiresName() throws IOException
        {
            ConfigurationException e = loadFailure("bot:\n  api_key: \"123456:ABCDEF\"\n");
            assertTrue(e.getMessage().contains("'name'"), e.getMessage());
        }

        @Test
        @DisplayName("a blank name is rejected")
        void rejectsBlankName() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: "   "
                      api_key: "123456:ABCDEF"
                    """);

            assertTrue(e.getMessage().contains("bot.name"), e.getMessage());
        }

        @Test
        @DisplayName("the api key is required")
        void requiresApiKey() throws IOException
        {
            ConfigurationException e = loadFailure("bot:\n  name: SpamProtectionBot\n");
            assertTrue(e.getMessage().contains("'api_key'"), e.getMessage());
        }

        @Test
        @DisplayName("an empty api key is rejected")
        void rejectsEmptyApiKey() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: ""
                    """);

            assertTrue(e.getMessage().contains("api_key"), e.getMessage());
        }

        @Test
        @DisplayName("a non-string name is rejected")
        void rejectsNonStringName() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: 42
                      api_key: "123456:ABCDEF"
                    """);

            assertTrue(e.getMessage().contains("must be a string"), e.getMessage());
        }

        @Test
        @DisplayName("a non-integer port is rejected")
        void rejectsNonIntegerPort() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      api_port: "443"
                    """);

            assertTrue(e.getMessage().contains("must be an integer"), e.getMessage());
        }

        @Test
        @DisplayName("a non-boolean test_server flag is rejected")
        void rejectsNonBooleanTestServer() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      test_server: yes-please
                    """);

            assertTrue(e.getMessage().contains("must be a boolean"), e.getMessage());
        }

        @Test
        @DisplayName("an unsupported api scheme is rejected")
        void rejectsUnsupportedScheme() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      api_scheme: ftp
                    """);

            assertTrue(e.getMessage().contains("api_scheme"), e.getMessage());
        }

        @Test
        @DisplayName("an out-of-range api port is rejected")
        void rejectsOutOfRangePort() throws IOException
        {
            assertTrue(loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      api_port: 0
                    """).getMessage().contains("api_port"));

            assertTrue(loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      api_port: 65536
                    """).getMessage().contains("api_port"));
        }

        @Test
        @DisplayName("a non-positive worker thread count is rejected")
        void rejectsNonPositiveWorkerThreads() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      worker_threads: 0
                    """);

            assertTrue(e.getMessage().contains("worker_threads"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive queue capacity is rejected")
        void rejectsNonPositiveQueueCapacity() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      queue_capacity: -1
                    """);

            assertTrue(e.getMessage().contains("queue_capacity"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive notification interval is rejected")
        void rejectsNonPositiveNotificationInterval() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      notification_interval_seconds: 0
                    """);

            assertTrue(e.getMessage().contains("notification_interval_seconds"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive connect timeout is rejected")
        void rejectsNonPositiveConnectTimeout() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      connect_timeout_seconds: 0
                    """);

            assertTrue(e.getMessage().contains("connect_timeout_seconds"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive read timeout is rejected")
        void rejectsNonPositiveReadTimeout() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      read_timeout_seconds: -1
                    """);

            assertTrue(e.getMessage().contains("read_timeout_seconds"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive write timeout is rejected")
        void rejectsNonPositiveWriteTimeout() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      write_timeout_seconds: 0
                    """);

            assertTrue(e.getMessage().contains("write_timeout_seconds"), e.getMessage());
        }

        @Test
        @DisplayName("a non-positive shutdown timeout is rejected")
        void rejectsNonPositiveShutdownTimeout() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      shutdown_timeout_seconds: 0
                    """);

            assertTrue(e.getMessage().contains("shutdown_timeout_seconds"), e.getMessage());
        }

        @Test
        @DisplayName("an empty optional string falls back to its default")
        void emptyOptionalFallsBackToDefault() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                      default_language: ""
                    """);

            assertEquals("en", configuration.getDefaultLanguage());
        }
    }

    @Nested
    @DisplayName("Validating the federation section")
    class FederationSection
    {
        @Test
        @DisplayName("the endpoint is required when the section is present")
        void requiresEndpoint() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      access_token: "abcdef"
                    """);

            assertTrue(e.getMessage().contains("'endpoint'"), e.getMessage());
        }

        @Test
        @DisplayName("the section must be a mapping")
        void requiresMapping() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation: "http://127.0.0.1:7000/"
                    """);

            assertTrue(e.getMessage().contains("Section 'federation' must be a mapping"), e.getMessage());
        }

        @Test
        @DisplayName("an endpoint without a host is rejected")
        void rejectsHostlessEndpoint() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      endpoint: "not-a-url"
                    """);

            assertTrue(e.getMessage().contains("federation.endpoint"), e.getMessage());
        }

        @Test
        @DisplayName("a malformed endpoint URL is rejected")
        void rejectsMalformedEndpoint() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      endpoint: "http://exa mple.com/"
                    """);

            assertTrue(e.getMessage().contains("federation.endpoint"), e.getMessage());
        }

        @Test
        @DisplayName("an access token containing whitespace is rejected")
        void rejectsWhitespaceInAccessToken() throws IOException
        {
            ConfigurationException e = loadFailure("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      endpoint: "http://127.0.0.1:7000/"
                      access_token: "abc def"
                    """);

            assertTrue(e.getMessage().contains("access_token"), e.getMessage());
        }

        @Test
        @DisplayName("an empty access token is treated as absent")
        void emptyAccessTokenIsAbsent() throws Exception
        {
            Configuration configuration = load("""
                    bot:
                      name: SpamProtectionBot
                      api_key: "123456:ABCDEF"
                    federation:
                      endpoint: "http://127.0.0.1:7000/"
                      access_token: ""
                    """);

            assertNull(configuration.getFederationAccessToken());
        }
    }
}
