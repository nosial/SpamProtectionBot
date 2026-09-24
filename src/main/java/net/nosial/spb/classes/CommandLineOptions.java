package net.nosial.spb.classes;

import net.nosial.spb.exceptions.CommandLineException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The options the process was started with.
 *
 * <p>Two resources have to be located before anything else can happen: the YAML configuration and
 * the SQLite database. Both have a default, so the bot starts with no arguments at all in a
 * directory that is laid out conventionally, and both can be pointed elsewhere for deployments
 * that keep their state outside the working directory.
 *
 * <p>Supported forms are {@code -c <path>}, {@code --config <path>}, {@code --config=<path>} and
 * the same three for {@code -d} / {@code --database}. Anything else is rejected with the usage
 * text rather than silently ignored.
 *
 * @param configuration the configuration file path
 * @param configurationExplicit whether the configuration path came from the command line rather
 *                              than the default
 * @param database the database file path
 * @param databaseExplicit whether the database path came from the command line rather than the
 *                         default
 * @param help whether the user asked for the usage text
 */
public record CommandLineOptions(Path configuration, boolean configurationExplicit,
        Path database, boolean databaseExplicit, boolean help)
{
    /** The configuration file used when {@code --config} is not given. */
    public static final String DEFAULT_CONFIGURATION = "configuration.yml";

    /** The database file used when {@code --database} is not given and the configuration names none. */
    public static final String DEFAULT_DATABASE = "./database.db";

    /**
     * Reads the options out of the raw command line.
     *
     * <p>Takes the arguments and works the rest out for itself, the way {@link Configuration}
     * takes a file path.
     *
     * @param args the arguments passed to {@code main}
     * @throws CommandLineException If an option is unknown, malformed, repeated, or missing its
     *                              value
     */
    public CommandLineOptions(String[] args)
    {
        this(parse(args));
    }

    /**
     * Copies the values read from the command line.
     *
     * @param parsed the options {@link #parse(String[])} read
     */
    private CommandLineOptions(CommandLineOptions parsed)
    {
        this(parsed.configuration(), parsed.configurationExplicit(), parsed.database(), parsed.databaseExplicit(), parsed.help());
    }

    /**
     * Reads the arguments into a set of options.
     *
     * @param args the arguments passed to {@code main}
     * @return the parsed options
     * @throws CommandLineException If an option is unknown, malformed, repeated, or missing its
     *                              value
     */
    private static CommandLineOptions parse(String[] args)
    {
        Objects.requireNonNull(args, "args must not be null");

        Path configuration = null;
        Path database = null;
        boolean help = false;

        for (int i = 0; i < args.length; i++)
        {
            String argument = args[i];

            if ("-h".equals(argument) || "--help".equals(argument))
            {
                help = true;
                continue;
            }

            if (!argument.startsWith("-"))
            {
                throw new CommandLineException("Unexpected argument '" + argument + "'."
                        + System.lineSeparator() + usage());
            }

            String name = argument;
            String inlineValue = null;
            int equals = argument.indexOf('=');
            if (equals >= 0)
            {
                name = argument.substring(0, equals);
                inlineValue = argument.substring(equals + 1);
            }

            switch (name)
            {
                case "-c", "--config" ->
                {
                    if (configuration != null)
                    {
                        throw new CommandLineException("Option '" + name + "' was given more than once." + System.lineSeparator() + usage());
                    }

                    i = valueIndex(args, i, name, inlineValue);
                    configuration = toPath(name, inlineValue != null ? inlineValue : args[i]);
                }
                case "-d", "--database" ->
                {
                    if (database != null)
                    {
                        throw new CommandLineException("Option '" + name + "' was given more than once." + System.lineSeparator() + usage());
                    }

                    i = valueIndex(args, i, name, inlineValue);
                    database = toPath(name, inlineValue != null ? inlineValue : args[i]);
                }
                default -> throw new CommandLineException("Unknown option '" + name + "'." + System.lineSeparator() + usage());
            }
        }

        return new CommandLineOptions(configuration != null ? configuration : Path.of(DEFAULT_CONFIGURATION),
                configuration != null, database != null ? database : Path.of(DEFAULT_DATABASE),
                database != null, help);
    }

    /**
     * Returns the index of the argument holding an option's value, consuming the next argument
     * when the value was not written inline.
     *
     * @param args the raw arguments
     * @param index the index of the option itself
     * @param name the option name, used in error messages
     * @param inlineValue the value written as {@code --option=value}, or {@code null}
     * @return the index the loop should continue from
     * @throws CommandLineException If the option has no value
     */
    private static int valueIndex(String[] args, int index, String name, String inlineValue)
    {
        if (inlineValue != null)
        {
            if (inlineValue.isBlank())
            {
                throw new CommandLineException("Option '" + name + "' needs a value." + System.lineSeparator() + usage());
            }
            return index;
        }

        if (index + 1 >= args.length)
        {
            throw new CommandLineException("Option '" + name + "' needs a value." + System.lineSeparator() + usage());
        }

        return index + 1;
    }

    /**
     * Converts an option's value into a path.
     *
     * @param name the option name, used in error messages
     * @param value the raw value
     * @return the path
     * @throws CommandLineException If the value is blank or not a valid path
     */
    private static Path toPath(String name, String value)
    {
        if (value == null || value.isBlank())
        {
            throw new CommandLineException("Option '" + name + "' needs a value." + System.lineSeparator() + usage());
        }

        try
        {
            return Path.of(value);
        }
        catch (InvalidPathException e)
        {
            throw new CommandLineException("Option '" + name + "' is not a valid path: " + value, e);
        }
    }

    /**
     * Returns the usage text shown by {@code --help} and appended to parse errors.
     *
     * @return the usage text
     */
    public static String usage()
    {
        return """
               SpamProtectionBot - Telegram spam protection backed by the Federated Database protocol

               Usage:
                 java -jar spb.jar [options]

               Options:
                 -c, --config <path>     Path to the YAML configuration file (default: %s)
                 -d, --database <path>   Path to the SQLite database file, created when missing
                                         (default: %s)
                 -h, --help              Show this help and exit
               """.formatted(DEFAULT_CONFIGURATION, DEFAULT_DATABASE);
    }
}
