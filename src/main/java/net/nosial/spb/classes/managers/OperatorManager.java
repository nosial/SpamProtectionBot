package net.nosial.spb.classes.managers;

import net.nosial.spb.exceptions.OperatorSnapshotException;
import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.OperatorIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code operators} table.
 *
 * <p>Provides create, read, update, and delete operations for operator identities linked to
 * Telegram users. Hot lookups are backed by an in-memory {@link Cache} so authorising every
 * incoming message does not hit SQLite. Writes are applied to the database first and only then
 * reflected in the cache, so a failed write can never leave a phantom cache entry.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class OperatorManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorManager.class);

    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long CACHE_EXPIRY_MINUTES = 10;

    /** How long the full operator table snapshot may be reused by {@link #listOperators()}. */
    private static final long OPERATOR_LIST_CACHE_SECONDS = 60;

    /** Fixed cache key holding the full operator table snapshot. */
    private static final String OPERATOR_LIST_KEY = "operators";

    private final Database database;
    private final Cache<Long, OperatorIdentity> cache;
    private final Cache<String, Map<Long, OperatorIdentity>> listCache;

    /**
     * Creates a new operator manager over the given database.
     *
     * @param database the database holding the {@code operators} table
     */
    public OperatorManager(Database database)
    {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.cache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.listCache = Cache.create(1, OPERATOR_LIST_CACHE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns the operator identity registered for the given Telegram user id.
     *
     * <p>Cache misses load the identity from the database, so the returned value always reflects
     * what was last durably written. A transient database read failure is logged and reported as
     * absent; a cached absence refreshes from the database once the cache entry expires.
     *
     * @param userId the Telegram user id
     * @return the operator identity, or {@link Optional#empty()} when no record exists
     */
    public Optional<OperatorIdentity> getOperator(long userId)
    {
        OperatorIdentity identity = this.cache.get(userId, this::loadOperator);
        return Optional.ofNullable(identity);
    }

    /**
     * Returns every Telegram user with a stored operator credential.
     *
     * <p>The result is the full table snapshot, cached for a short time (or until
     * {@link #saveOperator(long, OperatorIdentity)} or {@link #deleteOperator(long)} invalidates
     * it). Because every write to the table flows through this manager, the background
     * notification service observes new, changed, or removed operators promptly without paying a
     * full-table read on every poll cycle.
     *
     * @return immutable mapping from Telegram user id to operator identity
     * @throws DatabaseException if the operator table cannot be read
     */
    public Map<Long, OperatorIdentity> listOperators() throws DatabaseException
    {
        try
        {
            return this.listCache.get(OPERATOR_LIST_KEY, this::loadAllOperators);
        }
        catch (OperatorSnapshotException e)
        {
            throw e.cause();
        }
    }

    /**
     * Reads the complete operator table, used to fill the {@link #listCache} snapshot.
     *
     * @param ignored cache key, always the {@link #OPERATOR_LIST_KEY} constant
     * @return immutable mapping from Telegram user id to operator identity
     */
    private Map<Long, OperatorIdentity> loadAllOperators(String ignored)
    {
        Map<Long, OperatorIdentity> operators = new LinkedHashMap<>();
        try
        {
            this.database.query("SELECT * FROM operators ORDER BY id", null,
                            result -> Map.entry(result.getLong("id"), identity(result)))
                    .forEach(entry -> operators.put(entry.getKey(), entry.getValue()));
        }
        catch (DatabaseException e)
        {
            throw new OperatorSnapshotException(e);
        }
        return Map.copyOf(operators);
    }

    /**
     * Stores or replaces the operator identity for the given Telegram user id.
     *
     * <p>Writes are applied to the database first; the cache is only updated after the write
     * succeeds, so a crashed or failed write can never leave a cached identity that was never
     * persisted.
     *
     * @param userId the Telegram user id
     * @param identity the operator identity to store
     * @throws IllegalArgumentException if the identity is {@code null}
     * @throws DatabaseException if the write fails
     */
    public void saveOperator(long userId, OperatorIdentity identity) throws DatabaseException
    {
        Objects.requireNonNull(identity, "identity must not be null");
        this.database.execute("INSERT INTO operators (id, access_token, operator_uuid) VALUES (?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET access_token = excluded.access_token, "
                + "operator_uuid = excluded.operator_uuid", statement ->
        {
            statement.setLong(1, userId);
            statement.setString(2, identity.accessToken());
            statement.setString(3, identity.operatorUuid());
        });
        this.cache.put(userId, identity);
        this.listCache.remove(OPERATOR_LIST_KEY);
    }

    /**
     * Removes the operator identity registered for the given Telegram user id, if any.
     *
     * <p>As with {@link #saveOperator(long, OperatorIdentity)}, the deletion is applied to the
     * database first and the cache is invalidated only afterwards.
     *
     * @param userId the Telegram user id
     * @throws DatabaseException if the deletion fails
     */
    public void deleteOperator(long userId) throws DatabaseException
    {
        this.database.execute("DELETE FROM operators WHERE id = ?", statement -> statement.setLong(1, userId));
        this.cache.remove(userId);
        this.listCache.remove(OPERATOR_LIST_KEY);
    }

    /**
     * Loads an operator identity from the database, used to fill cache misses.
     *
     * @param userId the Telegram user id
     * @return the identity, or {@code null} when no record exists (never cached)
     */
    private OperatorIdentity loadOperator(long userId)
    {
        try
        {
            return this.database.queryOne("SELECT * FROM operators WHERE id = ?",
                    statement -> statement.setLong(1, userId), OperatorManager::identity).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load operator identity for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts an {@link OperatorIdentity} from the given {@link ResultSet}.
     *
     * @param result the {@link ResultSet} containing the operator identity data, expected to have
     *               columns "operator_uuid" and "access_token".
     * @return a new {@link OperatorIdentity} instance created from the extracted data.
     * @throws SQLException if a database access error occurs or the required columns do not exist.
     */
    private static OperatorIdentity identity(ResultSet result) throws SQLException
    {
        return new OperatorIdentity(result.getString("operator_uuid"), result.getString("access_token"));
    }
}