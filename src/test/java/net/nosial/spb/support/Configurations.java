package net.nosial.spb.support;

import net.nosial.spb.classes.Configuration;
import net.nosial.spb.exceptions.ConfigurationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds {@link Configuration} instances for tests.
 *
 * <p>A configuration can only be produced by reading a file, which is the point: tests that need
 * one write the same YAML an operator would, so the parsing and validation they depend on is the
 * parsing and validation that runs in production.
 */
public final class Configurations
{
    private Configurations()
    {
    }

    /**
     * Writes and loads a configuration with only the required fields.
     *
     * @param directory the directory to write the file into
     * @return the loaded configuration
     */
    public static Configuration minimal(Path directory)
    {
        return write(directory, """
                bot:
                  name: SpamProtectionBot
                  api_key: "123456:ABCDEF"
                """);
    }

    /**
     * Writes and loads a configuration with privacy mode turned on by default.
     *
     * @param directory the directory to write the file into
     * @return the loaded configuration
     */
    public static Configuration privacyMode(Path directory)
    {
        return write(directory, """
                bot:
                  name: SpamProtectionBot
                  api_key: "123456:ABCDEF"
                  privacy_mode: true
                """);
    }

    /**
     * Writes the given YAML to the directory and loads it.
     *
     * @param directory the directory to write into
     * @param yaml the configuration document
     * @return the loaded configuration
     */
    public static Configuration write(Path directory, String yaml)
    {
        try
        {
            Path path = Files.createTempFile(directory, "configuration-", ".yml");
            Files.writeString(path, yaml);
            return new Configuration(path);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        catch (ConfigurationException e)
        {
            throw new IllegalStateException("test configuration is not valid: " + e.getMessage(), e);
        }
    }
}
