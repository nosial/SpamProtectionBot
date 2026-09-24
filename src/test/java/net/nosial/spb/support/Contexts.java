package net.nosial.spb.support;

import net.nosial.spb.classes.BotServices;
import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.BotIdentity;
import net.nosial.spb.objects.context.HandlerContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds {@link HandlerContext} instances for tests.
 *
 * <p>A context is assembled the way the bot assembles one — from a configuration, a database, a
 * Federation service and a Telegram connection — so a test exercises the same wiring production
 * does. Only the Telegram connection is a stand-in, because opening a real one would mean a
 * network.
 */
public final class Contexts
{
    /** The identity tests run as. */
    public static final BotIdentity BOT = new BotIdentity(1000L, "testbot", "Test Bot");

    private static final LanguageManager LANGUAGES = new LanguageManager("en");

    private static HandlerContext shared;

    private Contexts()
    {
    }

    /**
     * Returns the shared language manager, loaded from the real language files.
     *
     * @return the language manager
     */
    public static LanguageManager languages()
    {
        return LANGUAGES;
    }

    /**
     * Returns a template context over a throwaway database, shared between tests that only read
     * updates.
     *
     * @return the template context
     */
    public static synchronized HandlerContext template()
    {
        if (shared == null)
        {
            shared = template(temporaryDirectory(), FederationService.unavailable());
        }

        return shared;
    }

    /**
     * Returns a template context over a database in the given directory.
     *
     * @param directory a directory the test owns
     * @return the template context
     */
    public static HandlerContext template(Path directory)
    {
        return template(directory, FederationService.unavailable());
    }

    /**
     * Returns a template context over a database in the given directory and the given Federation
     * server.
     *
     * @param directory a directory the test owns
     * @param federation the Federation server
     * @return the template context
     */
    public static HandlerContext template(Path directory, FederationService federation)
    {
        try
        {
            Database database = new Database(directory.resolve("database.db"));
            return new HandlerContext(new BotServices(Configurations.minimal(directory), database,
                    federation, new StubTelegramConnection(BOT)));
        }
        catch (DatabaseException e)
        {
            throw new IllegalStateException("test database could not be opened: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a directory that is cleaned up when the JVM exits.
     *
     * @return the directory
     */
    private static Path temporaryDirectory()
    {
        try
        {
            Path directory = Files.createTempDirectory("spb-test-context-");
            directory.toFile().deleteOnExit();
            return directory;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}
