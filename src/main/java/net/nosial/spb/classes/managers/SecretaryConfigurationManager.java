package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code secretary_configuration} table.
 *
 * <p>A configuration row exists only while the user's secretary mode is enabled: connecting creates
 * it with the schema's defaults and disconnecting removes it. Each setting is changed individually
 * by user id. Hot lookups are backed by an in-memory {@link Cache}.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class SecretaryConfigurationManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretaryConfigurationManager.class);

    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long CACHE_EXPIRY_MINUTES = 10;

    /** Matches configurations still waiting for their business connection id. */
    private static final String PENDING_CONNECTION = "business_connection_id IS NULL OR business_connection_id = ''";

    private final Database database;
    private final Cache<Long, SecretaryConfiguration> cache;
    private final Cache<String, Long> connectionCache;

    /**
     * Creates a new secretary configuration manager over the given database.
     *
     * @param database the database holding the {@code secretary_configuration} table
     */
    public SecretaryConfigurationManager(Database database)
    {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.cache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        this.connectionCache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
        migrateSchema();
    }

    /**
     * Returns the configuration for the given Telegram user id.
     *
     * <p>Cache misses load the configuration from the database. A transient database read failure
     * is logged and reported as absent; a cached absence refreshes from the database once the cache entry expires.
     *
     * @param userId the Telegram user id
     * @return the configuration, or {@link Optional#empty()} when no record exists
     */
    public Optional<SecretaryConfiguration> getSecretaryConfiguration(long userId)
    {
        return Optional.ofNullable(this.cache.get(userId, this::loadSecretaryConfiguration));
    }

    /**
     * Returns whether a configuration exists for the given Telegram user id.
     *
     * @param userId the Telegram user id
     * @return {@code true} when a record exists
     */
    public boolean secretaryConfigurationExists(long userId)
    {
        return getSecretaryConfiguration(userId).isPresent();
    }

    /**
     * Returns the configuration a user without a row is treated as having.
     *
     * @param userId the Telegram user id
     * @return a default secretary configuration
     */
    public SecretaryConfiguration defaultConfiguration(long userId)
    {
        return new SecretaryConfiguration(userId);
    }

    /**
     * Resolves the configuration for the given user, returning the stored configuration or a
     * default when none exists.
     *
     * @param userId the Telegram user id
     * @return the secretary configuration (never {@code null})
     */
    public SecretaryConfiguration resolve(long userId)
    {
        return getSecretaryConfiguration(userId).orElseGet(() -> defaultConfiguration(userId));
    }

    /**
     * Returns the Telegram user id owning the given business connection id, when the connection is
     * currently enabled in secretary mode.
     *
     * <p>The reverse mapping is cached because it is resolved for every incoming business message.
     * A cached mapping that no longer matches the owner's configuration is dropped, so writes never
     * have to track the connection id they replace.
     *
     * @param businessConnectionId the Telegram business connection id
     * @return the owning user id, or {@link Optional#empty()} when no configuration references the connection
     */
    public Optional<Long> userIdByBusinessConnection(String businessConnectionId)
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        Long userId = this.connectionCache.get(businessConnectionId, this::loadUserIdByBusinessConnection);
        if (userId != null && getSecretaryConfiguration(userId).filter(
                configuration -> businessConnectionId.equals(configuration.businessConnectionId())).isPresent())
        {
            return Optional.of(userId);
        }
        this.connectionCache.remove(businessConnectionId);
        return Optional.empty();
    }

    /**
     * Creates a user's configuration with the schema's defaults, unless one already exists.
     *
     * @param userId the Telegram user id
     * @throws DatabaseException if the write fails
     */
    public void createSecretaryConfiguration(long userId) throws DatabaseException
    {
        this.database.execute("INSERT OR IGNORE INTO secretary_configuration (id) VALUES (?)",
                statement -> statement.setLong(1, userId));
        this.cache.remove(userId);
    }

    /**
     * Binds a business connection to a user, creating the user's configuration when it has none.
     *
     * @param userId the Telegram user id
     * @param businessConnectionId the Telegram business connection id
     * @throws DatabaseException if the write fails
     */
    public void setBusinessConnectionId(long userId, String businessConnectionId) throws DatabaseException
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        this.database.execute("INSERT INTO secretary_configuration (id, business_connection_id) VALUES (?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET business_connection_id = excluded.business_connection_id", statement ->
        {
            statement.setLong(1, userId);
            statement.setString(2, businessConnectionId);
        });
        this.cache.remove(userId);
        this.connectionCache.put(businessConnectionId, userId);
    }

    /**
     * Sets how messages from unknown contacts are handled.
     *
     * @param userId the Telegram user id
     * @param behavior {@link ScanningBehavior#PASSIVE} or {@link ScanningBehavior#STRICT}
     * @throws IllegalArgumentException if the behavior is not supported by secretary mode
     * @throws DatabaseException if the write fails
     */
    public void setBehavior(long userId, ScanningBehavior behavior) throws DatabaseException
    {
        if (behavior != ScanningBehavior.PASSIVE && behavior != ScanningBehavior.STRICT)
        {
            throw new IllegalArgumentException("Secretary Mode supports only Passive and Strict behavior");
        }
        this.database.execute("UPDATE secretary_configuration SET behavior = ? WHERE id = ?", statement ->
        {
            statement.setString(1, behavior.name());
            statement.setLong(2, userId);
        });
        this.cache.remove(userId);
    }

    /**
     * Binds the given business connection id to the only user with a configuration that is still
     * awaiting a connection.
     *
     * <p>Business messages can arrive for connections whose {@code business_connection} update the
     * bot never saw (for example while polling was offline or starting late). Such a message cannot
     * identify the owning user from the message alone, but the user who started secretary mode from
     * the {@code Manage Bot} deep link has a configuration with an empty connection id. When exactly
     * one such pending configuration exists, this method ties the connection id to that user so the
     * message can be processed and later messages route normally.
     *
     * @param businessConnectionId the Telegram business connection id to bind
     * @return the newly bound user id, or {@link Optional#empty()} when none or several candidates exist
     */
    public Optional<Long> bindUnknownConnection(String businessConnectionId)
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        try
        {
            List<Long> candidates = this.database.query(
                    "SELECT id FROM secretary_configuration WHERE " + PENDING_CONNECTION + " LIMIT 2",
                    null, result -> result.getLong("id"));
            if (candidates.size() != 1)
            {
                return Optional.empty();
            }
            long userId = candidates.get(0);
            setBusinessConnectionId(userId, businessConnectionId);
            LOGGER.info("Bound business connection {} to the pending secretary configuration of user {}",
                    businessConnectionId, userId);
            return Optional.of(userId);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to bind business connection {}: {}", businessConnectionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Removes the configuration for the given Telegram user id, if any.
     *
     * @param userId the Telegram user id
     * @throws DatabaseException if the deletion fails
     */
    public void deleteSecretaryConfiguration(long userId) throws DatabaseException
    {
        this.database.execute("DELETE FROM secretary_configuration WHERE id = ?",
                statement -> statement.setLong(1, userId));
        this.cache.remove(userId);
    }

    /**
     * Loads the owner of a business connection from the database, used to fill cache misses.
     *
     * @param businessConnectionId the business connection id
     * @return the owning user id, or {@code null} when absent or the read failed
     */
    private Long loadUserIdByBusinessConnection(String businessConnectionId)
    {
        try
        {
            return this.database.queryOne("SELECT id FROM secretary_configuration WHERE business_connection_id = ?",
                    statement -> statement.setString(1, businessConnectionId),
                    result -> result.getLong("id")).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to resolve business connection {}: {}", businessConnectionId, e.getMessage());
            return null;
        }
    }

    /**
     * Loads a secretary configuration from the database, used to fill cache misses.
     *
     * @param userId the Telegram user id
     * @return the configuration, or {@code null} when no record exists or the read failed
     */
    private SecretaryConfiguration loadSecretaryConfiguration(long userId)
    {
        try
        {
            return this.database.queryOne("SELECT * FROM secretary_configuration WHERE id = ?",
                    statement -> statement.setLong(1, userId), result ->
                    {
                        ScanningBehavior behavior = ScanningBehavior.fromString(result.getString("behavior"));
                        return new SecretaryConfiguration(result.getLong("id"),
                                result.getString("business_connection_id"),
                                behavior == ScanningBehavior.MODERATE ? ScanningBehavior.PASSIVE : behavior,
                                false);
                    }).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load secretary configuration for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Migrates databases created while the configuration still used the scanning/unknown-protection
     * toggle columns: the new {@code behavior} column is filled from the former
     * {@code scanning_behavior} column and the obsolete columns are dropped.
     *
     * <p>Fresh databases created from schema 005 already have the final shape, so the migration is
     * a no-op there.
     */
    private void migrateSchema()
    {
        try (Connection connection = this.database.getConnection())
        {
            boolean hasBehavior = columnExists(connection, "behavior");
            boolean hasScanningBehavior = columnExists(connection, "scanning_behavior");
            boolean hasScanningEnabled = columnExists(connection, "scanning_enabled");
            boolean hasUnknownProtectionEnabled = columnExists(connection, "unknown_protection_enabled");
            boolean hasUnknownProtectionBehavior = columnExists(connection, "unknown_protection_behavior");

            if (!hasBehavior)
            {
                try (Statement statement = connection.createStatement())
                {
                    statement.execute("ALTER TABLE secretary_configuration "
                            + "ADD COLUMN behavior TEXT NOT NULL DEFAULT 'STRICT'");
                    LOGGER.info("Added missing column behavior to secretary_configuration");
                }
            }
            if (hasScanningBehavior)
            {
                try (Statement statement = connection.createStatement())
                {
                    // The former column defaulted to PASSIVE; only rows that were changed deserve
                    // the migrated behavior.
                    statement.execute("UPDATE secretary_configuration SET behavior = scanning_behavior "
                            + "WHERE scanning_behavior IS NOT NULL AND scanning_behavior != 'PASSIVE'");
                }
            }
            if (hasScanningEnabled)
            {
                dropColumn(connection, "scanning_enabled");
            }
            if (hasUnknownProtectionEnabled)
            {
                dropColumn(connection, "unknown_protection_enabled");
            }
            if (hasUnknownProtectionBehavior)
            {
                dropColumn(connection, "unknown_protection_behavior");
            }
            if (hasScanningBehavior)
            {
                dropColumn(connection, "scanning_behavior");
            }
        }
        catch (SQLException e)
        {
            LOGGER.warn("Failed to migrate the secretary_configuration schema: {}", e.getMessage());
        }
    }

    private static boolean columnExists(Connection connection, String column) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, null, "secretary_configuration", column))
        {
            return columns.next();
        }
    }

    private static void dropColumn(Connection connection, String column) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.execute("ALTER TABLE secretary_configuration DROP COLUMN " + column);
            LOGGER.info("Dropped obsolete column {} from secretary_configuration", column);
        }
    }
}