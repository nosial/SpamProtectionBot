package net.nosial.spb;

import net.nosial.jfederation.records.OperatorRecord;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.utilities.HostCanonicalizer;
import net.nosial.spb.classes.BotServices;
import net.nosial.spb.classes.CommandLineOptions;
import net.nosial.spb.classes.Configuration;
import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.HandlerRegistry;
import net.nosial.spb.classes.TelegramBot;
import net.nosial.spb.classes.UpdateDispatcher;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.classes.notifications.NotificationService;
import net.nosial.spb.exceptions.CommandLineException;
import net.nosial.spb.exceptions.ConfigurationException;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final Duration FEDERATION_AUTHENTICATION_RETRY = Duration.ofSeconds(30);

    private final Configuration configuration;
    private final Path databasePath;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private Database database;
    private FederationService federation;
    private TelegramBot bot;
    private UpdateDispatcher dispatcher;
    private NotificationService notifications;
    private ScheduledExecutorService federationAuthentication;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Creates the process over a validated configuration and a resolved database location.
     *
     * @param configuration the validated configuration
     * @param databasePath the location of the SQLite database file
     */
    public Main(Configuration configuration, Path databasePath)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null");
    }

    /**
     * Main execution point
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args)
    {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
        {
            try
            {
                LOGGER.error("Uncaught exception in thread '{}'", thread.getName(), throwable);
            }
            catch (Throwable ignored)
            {
                // Logging itself can fail while the JVM is running out of memory.
            }

            System.exit(1);
        });

        // Command-line options
        final CommandLineOptions options;
        try
        {
            options = new CommandLineOptions(args);
        }
        catch (CommandLineException e)
        {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        if (options.help())
        {
            System.out.println(CommandLineOptions.usage());
            return;
        }

        // Configuration File
        final Configuration configuration;
        try
        {
            configuration = new Configuration(options.configuration());
        }
        catch (ConfigurationException e)
        {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        LOGGER.info("Loaded configuration from {}", options.configuration().toAbsolutePath().normalize());
        final Main bot = new Main(configuration, options.database());
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "shutdown-hook"));

        // Start the Telegram bot
        try
        {
            bot.start();
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to start: {}", e.getMessage(), e);
            bot.close();
            System.exit(1);
            return;
        }

        try
        {
            bot.awaitShutdown();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            bot.close();
        }
    }

    /**
     * Opens the database, connects to Telegram, registers the handlers, and starts processing
     * updates.
     *
     * @throws DatabaseException If the database cannot be created, opened, or initialized
     * @throws TelegramApiException If the credentials are rejected or polling cannot start
     */
    public void start() throws DatabaseException, TelegramApiException
    {
        if (this.started)
        {
            throw new IllegalStateException("the bot has already started");
        }

        // Replace the bundled TLD snapshot with IANA's current list. It runs in the background: the
        // bundled list serves until then, and stays in use if the fetch fails.
        Thread tldRefresh = new Thread(HostCanonicalizer::refreshTlds, "tld-refresh");
        tldRefresh.setDaemon(true);
        tldRefresh.start();

        this.database = new Database(this.databasePath);
        Set<String> tables = this.database.tables();
        LOGGER.info("Database ready at {} with {} table(s): {}", this.database.path(), tables.size(), String.join(", ", tables));

        HandlerRegistry registry = new HandlerRegistry();
        this.federation = connectFederation();
        if (this.federation.isAuthenticationPending())
        {
            scheduleFederationAuthentication();
        }

        this.bot = new TelegramBot(this.configuration);
        this.bot.authenticate();

        BotServices services = new BotServices(this.configuration, this.database, this.federation, this.bot);
        NotificationFormatter.setLanguageManager(services.languages());

        this.dispatcher = new UpdateDispatcher(registry, new HandlerContext(services), this.configuration.getWorkerThreads(), this.configuration.getQueueCapacity());
        this.bot.startPolling(this.dispatcher);

        // Operators are told about the reports assigned to them by polling Federation, which only
        // makes sense once there is a Federation to poll.
        if (this.federation.isAvailable())
        {
            this.notifications = new NotificationService(services.managers(), this.bot.client(), this.federation, services.webLinks(), Duration.ofSeconds(this.configuration.getNotificationIntervalSeconds()));
            this.notifications.start();
        }

        this.started = true;
        LOGGER.info("{} started with {} worker thread(s) and a queue of {} update(s)",
                this.configuration.getName(), this.configuration.getWorkerThreads(),
                this.configuration.getQueueCapacity());
    }

    /**
     * Blocks the calling thread until the bot shuts down.
     *
     * @throws InterruptedException If the thread is interrupted while waiting
     */
    public void awaitShutdown() throws InterruptedException
    {
        this.shutdownLatch.await();
    }

    /**
     * Stops the bot: no further updates are fetched, everything already accepted is processed to
     * completion, and the database is closed last. Safe to call more than once.
     */
    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }
        this.closed = true;

        if (this.started)
        {
            LOGGER.info("Shutting down {}", this.configuration.getName());
        }

        // Stop fetching, both the update stream and the report notification poll.
        // Updates already handed to the dispatcher are untouched.
        if (this.bot != null)
        {
            this.bot.stopPolling();
        }

        if (this.federationAuthentication != null)
        {
            this.federationAuthentication.shutdownNow();
        }

        if (this.notifications != null)
        {
            try
            {
                this.notifications.close();
            }
            catch (Exception e)
            {
                LOGGER.warn("Failed to stop the notification service cleanly", e);
            }
        }

        // Let the queue run out, so every accepted update is finished rather than abandoned.
        if (this.dispatcher != null && this.dispatcher.drain(Duration.ofSeconds(this.configuration.getShutdownTimeoutSeconds())))
        {
            LOGGER.info("Processed {} update(s), {} dropped", this.dispatcher.processedUpdates(), this.dispatcher.droppedUpdates());
        }

        // Release the transport and the Federation client now that nothing needs them.
        if (this.bot != null)
        {
            this.bot.close();
        }

        if (this.federation != null)
        {
            this.federation.close();
        }

        // Close the database last: until step 2 returned, handlers could still be writing.
        if (this.database != null)
        {
            this.database.close();
        }

        this.started = false;
        this.shutdownLatch.countDown();
        LOGGER.info("Shutdown complete");
    }

    /**
     * Connects to the Federation server if one is configured.
     *
     * <p>An unreachable server is a warning, not a failure: the bot moderates, configures itself,
     * and answers commands without Federation, and the features that need it report themselves
     * unavailable until it comes back. Authentication is then left pending for
     * {@link #scheduleFederationAuthentication()} to retry.
     *
     * @return the Federation service, never {@code null}
     */
    private FederationService connectFederation()
    {
        if (!this.configuration.hasFederation())
        {
            LOGGER.warn("No 'federation' section in the configuration; running without Federation support");
            return FederationService.unavailable();
        }

        FederationService service = new FederationService(this.configuration.getFederationEndpoint(),
                this.configuration.getFederationAccessToken());

        try
        {
            ServerInformation information = service.serverInformation();
            LOGGER.info("Connected to Federation server '{}' (API {}) with {} known entities and {} blacklist records",
                    information.serverName(), information.apiVersion(), information.knownEntities(),
                    information.blacklistRecords());
        }
        catch (FederationException e)
        {
            LOGGER.warn("Federation server {} is unreachable or returned an error: {}",
                    this.configuration.getFederationEndpoint(), e.getMessage());
            return service;
        }

        String accessToken = this.configuration.getFederationAccessToken();
        if (accessToken == null || accessToken.isBlank())
        {
            LOGGER.info("No Federation access token configured; running as an anonymous client");
            return service;
        }

        authenticateFederation(service, false);
        return service;
    }

    /**
     * Retries authentication in the background until the server settles the bot's own access token,
     * so a Federation server that was down at startup, or went down mid-authentication, is
     * authenticated against as soon as it is reachable again rather than never.
     */
    private void scheduleFederationAuthentication()
    {
        LOGGER.info("Federation authentication is pending; retrying every {} second(s) until the server answers", FEDERATION_AUTHENTICATION_RETRY.toSeconds());

        this.federationAuthentication = Executors.newSingleThreadScheduledExecutor(task ->
        {
            Thread thread = new Thread(task, "federation-authentication");
            thread.setDaemon(true);
            return thread;
        });

        long delay = FEDERATION_AUTHENTICATION_RETRY.toMillis();
        this.federationAuthentication.scheduleWithFixedDelay(() ->
        {
            if (!this.federation.isAuthenticationPending())
            {
                this.federationAuthentication.shutdown();
                return;
            }

            authenticateFederation(this.federation, true);
        }, delay, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Confirms the bot's own access token carries client permissions, logging the outcome.
     *
     * <p>The server holds an authenticated-but-unprivileged token to a stricter standard than no
     * token at all: several calls an anonymous client may be allowed are refused outright to one
     * that identifies an operator without client permissions. {@link FederationService#authenticate()}
     * therefore discards a token that does not clear that bar, or that the server rejects, and the
     * bot is treated exactly like a host that never configured one. A failure that says nothing about
     * the token leaves authentication pending.
     *
     * @param service the service to authenticate
     * @param retrying whether this is a background retry, whose repeated connection failures are
     *                 logged quietly so an outage does not flood the log
     */
    private void authenticateFederation(FederationService service, boolean retrying)
    {
        try
        {
            OperatorRecord operator = service.authenticate();
            if (operator == null)
            {
                return;
            }

            if (service.isAuthenticated())
            {
                LOGGER.info("Authenticated with Federation as operator '{}' with client permissions", operator.name());
                return;
            }

            LOGGER.warn("Federation access token belongs to operator '{}' but lacks client permissions; " + "continuing as an anonymous client so publicly available features keep working", operator.name());
        }
        catch (FederationException e)
        {
            if (FederationService.isCredentialRejected(e))
            {
                LOGGER.warn("Federation access token was rejected ({}); continuing as an anonymous client", e.getMessage());
            }
            else if (retrying)
            {
                LOGGER.debug("Federation authentication is still pending: {}", e.getMessage());
            }
            else
            {
                LOGGER.warn("Could not authenticate with Federation ({}); will retry", e.getMessage());
            }
        }
    }
}
