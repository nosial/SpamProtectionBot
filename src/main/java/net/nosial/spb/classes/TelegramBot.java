package net.nosial.spb.classes;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.classes.interfaces.TelegramConnection;
import net.nosial.spb.objects.BotIdentity;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The bot's connection to Telegram: the API client and the long-polling session.
 *
 * <p>This class owns the transport and nothing else. It knows how to reach the configured API
 * host, who the bot is, and how to start and stop fetching updates; what to do with an update is
 * the dispatcher's business.
 *
 * <p>Lifecycle: {@link #authenticate()} verifies the credentials and resolves the bot's identity,
 * {@link #startPolling(LongPollingUpdateConsumer)} begins fetching updates, {@link #stopPolling()}
 * stops fetching without discarding what was already handed over, and {@link #close()} releases
 * the HTTP resources. Shutdown goes through {@link #stopPolling()} first so the dispatcher can
 * finish its queue knowing nothing new will arrive.
 */
public final class TelegramBot implements TelegramConnection, AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramBot.class);

    private final Configuration configuration;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final TelegramUrl telegramUrl;
    private final TelegramBotsLongPollingApplication application;
    private final OkHttpTelegramClient client;

    private BotIdentity identity;
    private BotSession session;

    /**
     * Creates the Telegram transport described by the configuration.
     *
     * <p>Nothing is sent yet: no request reaches Telegram until {@link #authenticate()}.
     *
     * @param configuration the validated configuration
     */
    public TelegramBot(Configuration configuration)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.objectMapper = new ObjectMapper();

        // The read timeout must outlast a long poll, which deliberately holds the connection open
        // until an update arrives or the server's own timeout expires.
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(configuration.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(configuration.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(configuration.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();

        this.telegramUrl = TelegramUrl.builder()
                .schema(configuration.getApiScheme())
                .host(configuration.getApiHost())
                .port(configuration.getApiPort())
                .testServer(configuration.isTestServer())
                .build();

        this.application = new TelegramBotsLongPollingApplication(() -> this.objectMapper, () -> this.httpClient);
        this.client = new OkHttpTelegramClient(this.objectMapper, this.httpClient, configuration.getApiKey(),
                this.telegramUrl);
    }

    /**
     * Verifies the API key against the configured host and resolves the bot's own identity.
     *
     * @return the bot's identity
     * @throws TelegramApiException If the key is rejected or the host is unreachable
     */
    public BotIdentity authenticate() throws TelegramApiException
    {
        User me = this.client.execute(new GetMe());
        this.identity = new BotIdentity(me.getId(), me.getUserName() != null ? me.getUserName() : "",
                me.getFirstName());

        LOGGER.info("Authenticated as {} ({})",
                this.identity.hasUsername() ? "@" + this.identity.username() : this.identity.id(),
                this.identity.name());

        return this.identity;
    }

    /**
     * Starts fetching updates and handing them to the given consumer.
     *
     * @param consumer the consumer updates are handed to
     * @throws TelegramApiException If the polling session cannot be started
     * @throws IllegalStateException If called before {@link #authenticate()} or twice
     */
    public void startPolling(LongPollingUpdateConsumer consumer) throws TelegramApiException
    {
        Objects.requireNonNull(consumer, "consumer must not be null");

        if (this.identity == null)
        {
            throw new IllegalStateException("authenticate() must succeed before polling starts");
        }

        if (this.session != null)
        {
            throw new IllegalStateException("polling has already started");
        }

        this.session = this.application.registerBot(this.configuration.getApiKey(), () -> this.telegramUrl,
                new DefaultGetUpdatesGenerator(), consumer);

        LOGGER.info("Polling {}://{}:{}{} for updates",
                this.configuration.getApiScheme(), this.configuration.getApiHost(), this.configuration.getApiPort(),
                this.configuration.isTestServer() ? " (test server)" : "");
    }

    /**
     * Stops fetching updates, leaving whatever was already handed to the consumer alone.
     *
     * <p>Safe to call when polling never started or already stopped.
     */
    public void stopPolling()
    {
        if (this.session == null)
        {
            return;
        }

        try
        {
            this.session.stop();
            LOGGER.info("Stopped polling for updates");
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Failed to stop the polling session cleanly", e);
        }
        finally
        {
            this.session = null;
        }
    }

    @Override
    public OkHttpTelegramClient client()
    {
        return this.client;
    }

    @Override
    public ObjectMapper objectMapper()
    {
        return this.objectMapper;
    }

    @Override
    public BotIdentity identity()
    {
        return this.identity;
    }

    @Override
    public void close()
    {
        stopPolling();

        try
        {
            this.application.close();
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to close the Telegram application cleanly", e);
        }

        this.httpClient.dispatcher().executorService().shutdown();
        this.httpClient.connectionPool().evictAll();
    }
}
