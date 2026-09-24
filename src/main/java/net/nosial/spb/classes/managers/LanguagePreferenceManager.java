package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code language_preferences} table, which stores both chat and user
 * language preferences distinguished by their {@code preference_type}.
 *
 * <p>Provides cached read and write-through operations for per-chat and per-user language
 * preferences. Hot lookups are backed by two in-memory {@link Cache} instances so the
 * database is not consulted on every notification dispatch.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class LanguagePreferenceManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LanguagePreferenceManager.class);

    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long CACHE_EXPIRY_MINUTES = 10;
    private static final String TYPE_CHAT = "CHAT";
    private static final String TYPE_USER = "USER";

    private final Database database;
    private final LanguageManager languages;
    private final Cache<Long, Language> chatCache;
    private final Cache<Long, Language> userCache;

    /**
     * Creates a new language preference manager over the given database.
     *
     * @param database the database holding the {@code language_preferences} table
     */
    public LanguagePreferenceManager(Database database, LanguageManager languages)
    {
        this.languages = Objects.requireNonNull(languages, "languages must not be null");
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.chatCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.userCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Returns the language this chat has chosen, or the bot's default when it has chosen none.
     *
     * @param chatId the Telegram chat id
     * @return the resolved language, never {@code null}
     */
    public Language getChatLanguage(long chatId)
    {
        return this.chatCache.get(chatId, id -> loadLanguage(TYPE_CHAT, id), this.languages.defaultLanguage());
    }

    /**
     * Returns the language this user has chosen, or the bot's default when they have chosen none.
     *
     * @param userId the Telegram user id
     * @return the resolved language, never {@code null}
     */
    public Language getUserLanguage(long userId)
    {
        return this.userCache.get(userId, id -> loadLanguage(TYPE_USER, id), this.languages.defaultLanguage());
    }

    /**
     * Stores or replaces the language preference for the given chat.
     *
     * @param chatId the Telegram chat id
     * @param language the language to set
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setChatLanguage(long chatId, Language language) throws DatabaseException
    {
        storeLanguage(TYPE_CHAT, chatId, language);
        this.chatCache.put(chatId, language);
    }

    /**
     * Moves a chat's language preference to a new chat id, for a group Telegram upgraded to a
     * supergroup. Nothing moves when the old chat has no preference or the new chat already has one.
     *
     * @param fromChatId the chat's previous id
     * @param toChatId the chat's new id
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void migrateChat(long fromChatId, long toChatId) throws DatabaseException
    {
        this.database.execute("UPDATE language_preferences SET preference_id = ? "
                + "WHERE preference_type = ? AND preference_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM language_preferences WHERE preference_type = ? AND preference_id = ?)", statement ->
        {
            statement.setLong(1, toChatId);
            statement.setString(2, TYPE_CHAT);
            statement.setLong(3, fromChatId);
            statement.setString(4, TYPE_CHAT);
            statement.setLong(5, toChatId);
        });
        this.chatCache.remove(fromChatId);
        this.chatCache.remove(toChatId);
    }

    /**
     * Stores or replaces the language preference for the given user.
     *
     * @param userId the Telegram user id
     * @param language the language to set
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setUserLanguage(long userId, Language language) throws DatabaseException
    {
        storeLanguage(TYPE_USER, userId, language);
        this.userCache.put(userId, language);
    }

    /**
     * Stores or updates the language preference for a given type and id in the database.
     *
     * @param type the type of preference, such as chat or user; must not be null
     * @param id the unique identifier for the preference type, such as a chat ID or user ID
     * @param language the language to store, represented by its code; must not be null
     * @throws DatabaseException if the database operation fails
     */
    private void storeLanguage(String type, long id, Language language) throws DatabaseException
    {
        Objects.requireNonNull(language, "language must not be null");
        this.database.execute("INSERT INTO language_preferences (preference_type, preference_id, language) "
                + "VALUES (?, ?, ?) ON CONFLICT(preference_type, preference_id) DO UPDATE SET language = excluded.language",
                statement ->
                {
                    statement.setString(1, type);
                    statement.setLong(2, id);
                    statement.setString(3, language.code());
                });
    }

    /**
     * Loads the language preference for a given type and ID from the database.
     * If no preference is found, the bot's default language is returned.
     *
     * @throws Cache.LoadFailedException If the preference cannot be read, so the failure is not cached
     *
     * @param type the type of preference (e.g., "chat" or "user"); must not be null
     * @param id the unique identifier for the preference type, such as a chat ID or user ID
     * @return the resolved language preference, or the default language if no preference is found
     */
    private Language loadLanguage(String type, long id)
    {
        try
        {
            return this.database.queryOne(
                    "SELECT language FROM language_preferences WHERE preference_type = ? AND preference_id = ?",
                    statement ->
                    {
                        statement.setString(1, type);
                        statement.setLong(2, id);
                    },
                    result -> this.languages.resolveOrDefault(result.getString("language")))
                    .orElseGet(this.languages::defaultLanguage);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load {} language for {}: {}", type.toLowerCase(), id, e.getMessage());
            throw new Cache.LoadFailedException("Failed to load " + type + " language for " + id, e);
        }
    }
}
