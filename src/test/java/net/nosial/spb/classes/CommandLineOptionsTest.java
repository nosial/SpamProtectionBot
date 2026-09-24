package net.nosial.spb.classes;

import net.nosial.spb.exceptions.CommandLineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for parsing the command line into the two paths the bot starts from.
 */
class CommandLineOptionsTest
{
    /**
     * Parses the given arguments.
     *
     * @param args the arguments
     * @return the parsed options
     */
    private static CommandLineOptions parse(String... args)
    {
        return new CommandLineOptions(args);
    }

    /**
     * Asserts that parsing the given arguments fails, and returns the exception.
     *
     * @param args the arguments
     * @return the thrown exception
     */
    private static CommandLineException failure(String... args)
    {
        return assertThrows(CommandLineException.class, () -> new CommandLineOptions(args));
    }

    @Nested
    @DisplayName("Applying defaults")
    class Defaults
    {
        @Test
        @DisplayName("no arguments means the conventional file names")
        void usesDefaults()
        {
            CommandLineOptions options = parse();

            assertEquals(Path.of("configuration.yml"), options.configuration());
            assertEquals(Path.of("./database.db"), options.database());
            assertFalse(options.configurationExplicit());
            assertFalse(options.databaseExplicit());
            assertFalse(options.help());
        }

        @Test
        @DisplayName("a default is reported as not explicitly given")
        void tracksWhichPathsWereGiven()
        {
            CommandLineOptions options = parse("--config", "other.yml");

            assertTrue(options.configurationExplicit());
            assertFalse(options.databaseExplicit());
        }
    }

    @Nested
    @DisplayName("Reading options")
    class Reading
    {
        @Test
        @DisplayName("the long form with a separate value is accepted")
        void readsLongForm()
        {
            CommandLineOptions options = parse("--config", "/etc/spb/configuration.yml",
                    "--database", "/var/lib/spb/database.db");

            assertEquals(Path.of("/etc/spb/configuration.yml"), options.configuration());
            assertEquals(Path.of("/var/lib/spb/database.db"), options.database());
        }

        @Test
        @DisplayName("the short form is accepted")
        void readsShortForm()
        {
            CommandLineOptions options = parse("-c", "custom.yml", "-d", "custom.db");

            assertEquals(Path.of("custom.yml"), options.configuration());
            assertEquals(Path.of("custom.db"), options.database());
        }

        @Test
        @DisplayName("an inline value is accepted in both forms")
        void readsInlineValue()
        {
            CommandLineOptions options = parse("--config=inline.yml", "-d=inline.db");

            assertEquals(Path.of("inline.yml"), options.configuration());
            assertEquals(Path.of("inline.db"), options.database());
        }

        @Test
        @DisplayName("options may be given in any order")
        void orderDoesNotMatter()
        {
            CommandLineOptions options = parse("-d", "state.db", "--config", "settings.yml");

            assertEquals(Path.of("settings.yml"), options.configuration());
            assertEquals(Path.of("state.db"), options.database());
        }

        @Test
        @DisplayName("help is recognised in both forms and alongside other options")
        void readsHelp()
        {
            assertTrue(parse("--help").help());
            assertTrue(parse("-h").help());
            assertTrue(parse("--config", "a.yml", "--help").help());
        }
    }

    @Nested
    @DisplayName("Rejecting bad arguments")
    class Rejecting
    {
        @Test
        @DisplayName("an unknown option is rejected with the usage text")
        void rejectsUnknownOption()
        {
            CommandLineException e = failure("--verbose");

            assertTrue(e.getMessage().contains("--verbose"), e.getMessage());
            assertTrue(e.getMessage().contains("Usage:"), e.getMessage());
        }

        @Test
        @DisplayName("a stray positional argument is rejected")
        void rejectsPositionalArgument()
        {
            CommandLineException e = failure("configuration.yml");
            assertTrue(e.getMessage().contains("configuration.yml"), e.getMessage());
        }

        @Test
        @DisplayName("an option without a value is rejected")
        void rejectsMissingValue()
        {
            assertTrue(failure("--config").getMessage().contains("needs a value"));
            assertTrue(failure("-d").getMessage().contains("needs a value"));
            assertTrue(failure("--config=").getMessage().contains("needs a value"));
        }

        @Test
        @DisplayName("repeating an option is rejected rather than silently overridden")
        void rejectsRepeatedOption()
        {
            assertTrue(failure("-c", "a.yml", "--config", "b.yml").getMessage().contains("more than once"));
            assertTrue(failure("-d", "a.db", "-d", "b.db").getMessage().contains("more than once"));
        }
    }

    @Nested
    @DisplayName("Describing itself")
    class Usage
    {
        @Test
        @DisplayName("the usage text names every option and default")
        void usageIsComplete()
        {
            String usage = CommandLineOptions.usage();

            assertTrue(usage.contains("--config"), usage);
            assertTrue(usage.contains("--database"), usage);
            assertTrue(usage.contains("--help"), usage);
            assertTrue(usage.contains(CommandLineOptions.DEFAULT_CONFIGURATION), usage);
            assertTrue(usage.contains(CommandLineOptions.DEFAULT_DATABASE), usage);
        }
    }
}
