package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code users} table.
 *
 * <p>Provides create, read, update, and delete operations for Telegram user identities. Users can
 * be looked up either by Telegram user id or by username. The id cache stores the full
 * {@link UserIdentity}; the username cache stores the mapping from username to user id. When a
 * username changes, the old username entry is invalidated from the reverse cache before the new
 * mapping is stored, so stale lookups resolve correctly.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class UserManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UserManager.class);

    private static final int CACHE_MAX_SIZE = 100_000;
    private static final long CACHE_EXPIRY_MINUTES = 10;

    private final Database database;
    private final Cache<Long, UserIdentity> byIdCache;
    private final Cache<String, Long> byUsernameCache;

    /**
     * Creates a new user manager over the given database.
     *
     * @param database the database holding the {@code users} table
     */
    public UserManager(Database database)
    {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.byIdCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.byUsernameCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        ensureColumns();
    }

    /**
     * Ensures first_name and last_name columns exist (for existing databases).
     */
    private void ensureColumns()
    {
        try (Connection connection = this.database.getConnection())
        {
            var meta = connection.getMetaData();
            var columns = meta.getColumns(null, null, "users", "first_name");
            if (!columns.next())
            {
                try (var stmt = connection.createStatement())
                {
                    stmt.execute("ALTER TABLE users ADD COLUMN first_name TEXT");
                }
            }
            columns = meta.getColumns(null, null, "users", "last_name");
            if (!columns.next())
            {
                try (var stmt = connection.createStatement())
                {
                    stmt.execute("ALTER TABLE users ADD COLUMN last_name TEXT");
                }
            }
        }
        catch (SQLException e)
        {
            LOGGER.warn("Failed to ensure user name columns: {}", e.getMessage());
        }
    }

    /**
     * Returns the identity registered for the given Telegram user id.
     *
     * <p>Cache misses load the identity from the database, so the returned value always reflects
     * what was last durably written. A transient database read failure is logged and reported as
     * absent; a cached absence refreshes from the database once the cache entry expires.
     *
     * @param userId the Telegram user id
     * @return the identity, or {@link Optional#empty()} when no record exists
     */
    public Optional<UserIdentity> getUser(long userId)
    {
        UserIdentity identity = this.byIdCache.get(userId, this::loadUserById);
        return Optional.ofNullable(identity);
    }

    /**
     * Returns the Telegram user id associated with the given username.
     *
     * <p>Usernames are matched case-insensitively and without the leading '@'. Cache misses load
     * the mapping from the database. A transient database read failure is logged and reported as
     * absent.
     *
     * @param username the Telegram username, with or without the leading '@'
     * @return the user id, or {@link Optional#empty()} when no record exists
     */
    public Optional<Long> getUserIdByUsername(String username)
    {
        String normalized = normalizeUsername(username);
        if (normalized == null)
        {
            return Optional.empty();
        }

        Long userId = this.byUsernameCache.get(normalized, this::loadUserIdByUsername);
        return Optional.ofNullable(userId);
    }

    /**
     * Returns whether a user identity exists for the given Telegram user id.
     *
     * @param userId the Telegram user id
     * @return {@code true} when a record exists
     */
    public boolean userExists(long userId)
    {
        return getUser(userId).isPresent();
    }

    /**
     * Stores or replaces the identity for the given Telegram user.
     *
     * <p>Writes are applied to the database first; the caches are only updated after the write
     * succeeds. If the username changed since the last known identity, the old username mapping is
     * invalidated from the reverse cache before the new mapping is stored.
     *
     * <p>Telegram usernames can move between accounts, but the column is unique, so a username still
     * recorded against another user is released from that user in the same transaction.
     *
     * @param identity the user identity to store
     * @throws IllegalArgumentException if the identity is {@code null}
     * @throws DatabaseException if the write fails
     */
    public void saveUser(UserIdentity identity) throws DatabaseException
    {
        Objects.requireNonNull(identity, "identity must not be null");
        String normalizedUsername = normalizeUsername(identity.username());

        UserIdentity previous = getUser(identity.userId()).orElse(null);
        String previousUsername = previous != null ? normalizeUsername(previous.username()) : null;
        if (previousUsername != null && !previousUsername.equals(normalizedUsername))
        {
            this.byUsernameCache.remove(previousUsername);
        }

        List<Long> previousOwners = this.database.transaction(connection ->
        {
            List<Long> owners = new ArrayList<>();
            if (normalizedUsername != null)
            {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id FROM users WHERE username = ? AND id != ?"))
                {
                    select.setString(1, normalizedUsername);
                    select.setLong(2, identity.userId());
                    try (ResultSet result = select.executeQuery())
                    {
                        while (result.next())
                        {
                            owners.add(result.getLong("id"));
                        }
                    }
                }
                try (PreparedStatement release = connection.prepareStatement(
                        "UPDATE users SET username = NULL WHERE username = ? AND id != ?"))
                {
                    release.setString(1, normalizedUsername);
                    release.setLong(2, identity.userId());
                    release.executeUpdate();
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO users (id, username, first_name, last_name) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET username = excluded.username, "
                            + "first_name = excluded.first_name, last_name = excluded.last_name"))
            {
                upsert.setLong(1, identity.userId());
                upsert.setString(2, normalizedUsername);
                upsert.setString(3, identity.firstName());
                upsert.setString(4, identity.lastName());
                upsert.executeUpdate();
            }
            return owners;
        });

        previousOwners.forEach(this.byIdCache::remove);
        this.byIdCache.put(identity.userId(), identity);
        if (normalizedUsername != null)
        {
            this.byUsernameCache.put(normalizedUsername, identity.userId());
        }
    }

    /**
     * Removes the identity registered for the given Telegram user id, if any.
     *
     * <p>As with {@link #saveUser(UserIdentity)}, the deletion is applied to the database first and
     * the caches are invalidated only afterwards.
     *
     * @param userId the Telegram user id
     * @throws DatabaseException if the deletion fails
     */
    public void deleteUser(long userId) throws DatabaseException
    {
        UserIdentity previous = getUser(userId).orElse(null);

        this.database.execute("DELETE FROM users WHERE id = ?", statement -> statement.setLong(1, userId));

        this.byIdCache.remove(userId);
        if (previous != null && previous.username() != null)
        {
            this.byUsernameCache.remove(normalizeUsername(previous.username()));
        }
    }

    /**
     * Loads a user identity from the database, used to fill cache misses.
     *
     * @param userId the Telegram user id
     * @return the identity, or {@code null} when no record exists (never cached)
     */
    private UserIdentity loadUserById(long userId)
    {
        try
        {
            return this.database.queryOne("SELECT * FROM users WHERE id = ?", statement -> statement.setLong(1, userId),
                    result -> new UserIdentity(userId, result.getString("username"), result.getString("first_name"),
                            result.getString("last_name"))).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load user identity for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Loads a user id from the database by username, used to fill cache misses.
     *
     * @param username the normalized username
     * @return the user id, or {@code null} when no record exists (never cached)
     */
    private Long loadUserIdByUsername(String username)
    {
        try
        {
            return this.database.queryOne("SELECT id FROM users WHERE username = ?",
                    statement -> statement.setString(1, username), result -> result.getLong("id")).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load user id for username {}: {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes a Telegram username by stripping a leading '@' and converting to lower case.
     *
     * @param username the raw username, may be {@code null} or blank
     * @return the normalized username, or {@code null} when empty
     */
    private static String normalizeUsername(String username)
    {
        if (username == null || username.isBlank())
        {
            return null;
        }

        String normalized = username.trim();
        if (normalized.startsWith("@"))
        {
            normalized = normalized.substring(1);
        }

        normalized = normalized.toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }
}