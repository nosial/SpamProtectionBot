package net.nosial.spb.classes.sessions;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.interfaces.Session;
import net.nosial.spb.classes.interfaces.TouchableSession;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Base class for all in-memory session managers.
 *
 * <p>Provides the shared infrastructure that every session manager needs: opaque hash generation,
 * a bounded expiring cache, and a {@link #find(String)} method that refreshes the expiry of
 * {@link TouchableSession} entries on access. Subclasses focus only on their domain-specific
 * creation, mutation, and consumption logic.
 *
 * @param <V> the session type stored by this manager
 */
public abstract class AbstractSessionManager<V extends Session>
{
    private static final int HASH_BYTES = 12;
    private static final int CACHE_MAX_SIZE = 10_000;

    private final Cache<String, V> cache;
    private final SecureRandom random;

    /**
     * Creates a new session manager with the given expiry duration.
     *
     * @param expiry the duration after which entries expire (must be {@code > 0})
     * @param unit the time unit of the expiry duration
     */
    protected AbstractSessionManager(long expiry, TimeUnit unit)
    {
        this.cache = Cache.create(CACHE_MAX_SIZE, expiry, unit);
        this.random = new SecureRandom();
    }

    /**
     * Looks up an active session by hash. When the session implements {@link TouchableSession},
     * its expiry is refreshed by re-inserting it into the cache.
     *
     * @param hash the session hash
     * @return the session, or {@code null} when absent or expired
     */
    public V find(String hash)
    {
        V session = this.cache.getIfPresent(hash);
        if (session == null)
        {
            return null;
        }
        if (session instanceof TouchableSession touchable)
        {
            @SuppressWarnings("unchecked")
            V touched = (V) touchable.touch();
            this.cache.put(hash, touched);
            return touched;
        }
        return session;
    }

    /**
     * Removes the session from the store.
     *
     * @param hash the session hash
     */
    public void invalidate(String hash)
    {
        this.cache.remove(hash);
    }

    /**
     * Generates a new opaque session hash and stores the session in the cache.
     *
     * @param session the session to store (must have a non-null {@link Session#hash()})
     * @return the stored session
     */
    protected V store(V session)
    {
        this.cache.put(session.hash(), session);
        return session;
    }

    /**
     * Removes the session from the cache.
     *
     * @param session the session to remove
     */
    protected void evict(V session)
    {
        this.cache.remove(session.hash());
    }

    /**
     * Generates an opaque, URL-safe session hash.
     *
     * @return a new hash string
     */
    protected String generateHash()
    {
        byte[] bytes = new byte[HASH_BYTES];
        this.random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returns the underlying cache for subclasses that need direct access
     * (e.g. ownership checks, atomic take operations).
     *
     * @return the session cache
     */
    protected Cache<String, V> cache()
    {
        return this.cache;
    }

    /**
     * A bidirectional mapping from a secondary lookup key to a session hash.
     *
     * <p>Used by session managers that need to resolve sessions by a key other than the primary
     * hash — for example, a prompt message id or a channel verification code. The index entries
     * expire alongside the main session cache.
     *
     * @param <K> the secondary lookup key type
     */
    protected static final class ReverseIndex<K>
    {
        private final Cache<K, String> index;

        /**
         * Creates a reverse index with the same expiry characteristics as the main cache.
         *
         * @param expiry the duration after which entries expire
         * @param unit the time unit of the expiry duration
         */
        public ReverseIndex(long expiry, TimeUnit unit)
        {
            this.index = Cache.create(CACHE_MAX_SIZE, expiry, unit);
        }

        /**
         * Stores a mapping from the secondary key to the session hash.
         *
         * @param key the secondary lookup key
         * @param hash the session hash
         */
        public void store(K key, String hash)
        {
            this.index.put(key, hash);
        }

        /**
         * Removes the mapping for the given key.
         *
         * @param key the secondary lookup key
         */
        public void clear(K key)
        {
            if (key != null)
            {
                this.index.remove(key);
            }
        }

        /**
         * Resolves the session hash for the given secondary key.
         *
         * @param key the secondary lookup key
         * @return the session hash, or {@code null} when absent
         */
        public String resolve(K key)
        {
            return this.index.getIfPresent(key);
        }
    }
}
