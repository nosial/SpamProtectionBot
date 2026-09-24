package net.nosial.spb.classes;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.classes.interfaces.TelegramConnection;
import net.nosial.spb.classes.managers.ManagerRegistry;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.BotIdentity;
import net.nosial.spb.objects.ChatInfo;
import net.nosial.spb.objects.SessionRegistry;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Everything the bot owns for its whole run, assembled from the four things it is given.
 *
 * <p>A handler needs a dozen collaborators, but nobody should have to name a dozen collaborators
 * to create one. This takes the configuration, the open database, the connected bot and the
 * Federation server, and works out the rest for itself: the translations come from the files on
 * the classpath, the managers from the database and that configuration, the session managers and
 * the caches from nothing at all.
 *
 * <p>That is the same idea as {@link Configuration}, which turns one file path into every setting.
 * Give a constructor the real things and let it derive the details, rather than making every
 * caller spell them out.
 *
 * <p>Everything here is created once and shared by every worker thread, so all of it is
 * thread-safe.
 */
public final class BotServices
{
    /** Maximum number of entries in the runtime cache shared by every handler. */
    private static final int RUNTIME_CACHE_MAX_SIZE = 100_000;

    /** How long an entry stays in the runtime cache before it is evicted. */
    private static final long RUNTIME_CACHE_EXPIRY_MINUTES = 10;

    /** Maximum number of chats whose administrator list is kept in memory. */
    private static final int CHAT_ADMIN_CACHE_MAX_SIZE = 10_000;

    /** How long a cached administrator list stays valid before it is refetched. */
    private static final long CHAT_ADMIN_CACHE_EXPIRY_SECONDS = 120;

    /** Maximum number of chats whose name and type are kept in memory. */
    private static final int CHAT_INFO_CACHE_MAX_SIZE = 10_000;

    /** How long a cached chat snapshot stays valid before it is evicted. */
    private static final long CHAT_INFO_CACHE_EXPIRY_SECONDS = 300;

    private final Configuration configuration;
    private final Database database;
    private final FederationService federation;
    private final TelegramConnection bot;

    private final LanguageManager languages;
    private final ManagerRegistry managers;
    private final SessionRegistry sessions;
    private final Cache<String, Object> cache;
    private final Cache<Long, List<AdminInfo>> chatAdmins;
    private final Cache<Long, ChatInfo> chatInfo;

    /**
     * Assembles the bot's services.
     *
     * <p>The bot must already have authenticated, since its identity is read here and every
     * handler depends on knowing who it is.
     *
     * @param configuration the validated process configuration
     * @param database the open database
     * @param federation the Federation server, which may report itself unavailable
     * @param bot the authenticated Telegram connection
     * @throws IllegalStateException If the bot has not authenticated yet
     */
    public BotServices(Configuration configuration, Database database, FederationService federation,
                       TelegramConnection bot)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.federation = Objects.requireNonNull(federation, "federation must not be null");
        this.bot = Objects.requireNonNull(bot, "bot must not be null");

        if (bot.identity() == null)
        {
            throw new IllegalStateException("the bot must authenticate before its services are assembled");
        }

        this.languages = new LanguageManager(configuration.getDefaultLanguage());
        this.managers = new ManagerRegistry(database, configuration, this.languages);
        this.sessions = new SessionRegistry();
        this.cache = Cache.create(RUNTIME_CACHE_MAX_SIZE, RUNTIME_CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.chatAdmins = Cache.create(CHAT_ADMIN_CACHE_MAX_SIZE, CHAT_ADMIN_CACHE_EXPIRY_SECONDS, TimeUnit.SECONDS);
        this.chatInfo = Cache.create(CHAT_INFO_CACHE_MAX_SIZE, CHAT_INFO_CACHE_EXPIRY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns the validated process configuration.
     *
     * @return the configuration
     */
    public Configuration configuration()
    {
        return this.configuration;
    }

    /**
     * Returns the SQLite access layer.
     *
     * @return the database
     */
    public Database database()
    {
        return this.database;
    }

    /**
     * Returns the record-keeping layer over the database.
     *
     * @return the managers
     */
    public ManagerRegistry managers()
    {
        return this.managers;
    }

    /**
     * Returns the Federation server.
     *
     * @return the Federation service
     */
    public FederationService federation()
    {
        return this.federation;
    }

    /**
     * Returns the translations discovered on the classpath.
     *
     * @return the language manager
     */
    public LanguageManager languages()
    {
        return this.languages;
    }

    /**
     * Returns the short-lived dialogs currently open.
     *
     * @return the session registry
     */
    public SessionRegistry sessions()
    {
        return this.sessions;
    }

    /**
     * Returns the shared cache for transient runtime state.
     *
     * @return the runtime cache
     */
    public Cache<String, Object> cache()
    {
        return this.cache;
    }

    /**
     * Returns the per-chat administrator snapshots.
     *
     * @return the administrator cache
     */
    public Cache<Long, List<AdminInfo>> chatAdmins()
    {
        return this.chatAdmins;
    }

    /**
     * Returns the per-chat name and type snapshots.
     *
     * @return the chat information cache
     */
    public Cache<Long, ChatInfo> chatInfo()
    {
        return this.chatInfo;
    }

    /**
     * Returns the Telegram API client.
     *
     * @return the client
     */
    public OkHttpTelegramClient telegramClient()
    {
        return this.bot.client();
    }

    /**
     * Returns the Jackson mapper the Telegram client serialises with.
     *
     * @return the object mapper
     */
    public ObjectMapper objectMapper()
    {
        return this.bot.objectMapper();
    }

    /**
     * Returns the bot's own identity as Telegram reported it.
     *
     * @return the identity
     */
    public BotIdentity identity()
    {
        return this.bot.identity();
    }
}
