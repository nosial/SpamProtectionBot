package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.Database;
import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.SecretaryContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manager for the {@code secretary_contacts} table: the people who have written to a
 * secretary-enabled business connection, keyed by connection id and user id.
 *
 * <p>This class is thread-safe and may be called from many worker threads concurrently.
 */
public final class SecretaryContactManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretaryContactManager.class);
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long CACHE_EXPIRY_MINUTES = 10;

    private final Database database;
    private final Cache<String, SecretaryContact> cache;

    public SecretaryContactManager(Database database)
    {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.cache = Cache.create(CACHE_MAX_SIZE, CACHE_EXPIRY_MINUTES, TimeUnit.MINUTES);
    }

    public Optional<SecretaryContact> getSecretaryContact(String businessConnectionId, long id)
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        return Optional.ofNullable(this.cache.get(cacheKey(businessConnectionId, id),
                ignored -> loadSecretaryContact(businessConnectionId, id)));
    }

    /**
     * Records a contact the first time they are seen. A contact that is already tracked is left
     * as it is.
     *
     * @param contact the contact to record
     * @throws DatabaseException if the write fails
     */
    public void registerSecretaryContact(SecretaryContact contact) throws DatabaseException
    {
        Objects.requireNonNull(contact, "contact must not be null");
        int inserted = this.database.execute("INSERT OR IGNORE INTO secretary_contacts "
                + "(business_connection_id, id, status, first_seen_at) VALUES (?, ?, ?, ?)", statement ->
        {
            statement.setString(1, contact.businessConnectionId());
            statement.setLong(2, contact.id());
            statement.setString(3, contact.status().name());
            statement.setLong(4, contact.firstSeenAt());
        });
        String key = cacheKey(contact.businessConnectionId(), contact.id());
        if (inserted > 0)
        {
            this.cache.put(key, contact);
            return;
        }
        this.cache.remove(key);
    }

    /**
     * Sets a contact's status, starting to track the contact when it is not tracked yet (for
     * example when the owner decides on somebody before they have written).
     *
     * @param businessConnectionId the business connection the contact belongs to
     * @param id The Telegram user id of the contact
     * @param status the new status
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void setSecretaryContactStatus(String businessConnectionId, long id,
                                          SecretaryContactStatus status)
            throws DatabaseException
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        this.database.execute("INSERT INTO secretary_contacts (business_connection_id, id, status, first_seen_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT(business_connection_id, id) DO UPDATE SET status = excluded.status",
                statement ->
                {
                    statement.setString(1, businessConnectionId);
                    statement.setLong(2, id);
                    statement.setString(3, status.name());
                    statement.setLong(4, System.currentTimeMillis() / 1_000);
                });
        this.cache.remove(cacheKey(businessConnectionId, id));
    }

    /**
     * Removes every contact stored for the given business connection. Used when secretary mode is
     * disabled.
     *
     * @param businessConnectionId the business connection whose contacts are removed
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void deleteSecretaryContacts(String businessConnectionId) throws DatabaseException
    {
        Objects.requireNonNull(businessConnectionId, "businessConnectionId must not be null");
        this.database.execute("DELETE FROM secretary_contacts WHERE business_connection_id = ?",
                statement -> statement.setString(1, businessConnectionId));
        this.cache.clear();
    }

    /**
     * Removes every secretary contact. Used when secretary mode is disabled but the owning
     * business connection id is no longer known, so contacts cannot be matched by connection.
     *
     * @throws DatabaseException If there is an error while updating the database.
     */
    public void deleteAllSecretaryContacts() throws DatabaseException
    {
        this.database.execute("DELETE FROM secretary_contacts", null);
        this.cache.clear();
    }

    /**
     * Loads a secretary contact from the database using the given business connection ID and contact ID.
     *
     * @param businessConnectionId the unique identifier of the business connection
     * @param id the unique identifier of the secretary contact
     * @return the {@code SecretaryContact} corresponding to the given identifiers,
     *         or {@code null} if no matching contact is found or in case of a database access failure
     */
    private SecretaryContact loadSecretaryContact(String businessConnectionId, long id)
    {
        try
        {
            return this.database.queryOne("SELECT * FROM secretary_contacts WHERE business_connection_id = ? AND id = ?",
                    statement ->
                    {
                        statement.setString(1, businessConnectionId);
                        statement.setLong(2, id);
                    },
                    result -> new SecretaryContact(result.getString("business_connection_id"), result.getLong("id"),
                            SecretaryContactStatus.fromString(result.getString("status")),
                            result.getLong("first_seen_at"))).orElse(null);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to load secretary contact {} in connection {}: {}", id, businessConnectionId, e.getMessage());
            return null;
        }
    }

    /**
     * Generates a unique cache key for identifying a specific secretary contact
     * based on the business connection ID and the contact's ID.
     *
     * @param businessConnectionId the unique identifier of the business connection
     * @param id the unique identifier of the contact
     * @return a string representing the concatenated cache key in the format "{businessConnectionId}:{id}"
     */
    private static String cacheKey(String businessConnectionId, long id)
    {
        return businessConnectionId + ":" + id;
    }
}
