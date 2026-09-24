package net.nosial.spb.classes;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Thread-safe, high-performance in-memory cache used as the runtime information layer of the bot.
 *
 * <p>Backed by Caffeine: concurrent reads and writes never block each other, entries are evicted
 * by size and (optionally) by write expiry, and lookups may load values on demand through a
 * caller-supplied loader function. A cache instance is safe to share between all worker threads.
 *
 * <p>Absent results are cached too: when a loader returns {@code null}, the key is retained as
 * "absent" until it is written to, invalidated, or expires, so repeated lookups of a missing row
 * (an unconfigured chat, an unknown user, ...) do not re-hit the database. The public methods
 * translate that state back to {@code null}, so callers cannot observe the sentinel.
 *
 * <p>Typical usage:
 * <pre>{@code Cache<Long, UserIdentity> users = Cache.create(100_000, 10, TimeUnit.MINUTES);}</pre>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class Cache<K, V>
{
    /** Sentinel stored for loaders that returned {@code null}, representing an explicit absence. */
    private static final Object ABSENT = new Object();

    private final com.github.benmanes.caffeine.cache.Cache<K, Object> cache;

    private Cache(com.github.benmanes.caffeine.cache.Cache<K, Object> cache)
    {
        this.cache = cache;
    }

    /**
     * Creates a cache bounded by size only, with no expiry.
     *
     * <p>Suitable for runtime information that must live for the whole process lifetime.
     *
     * @param maximumSize the maximum number of entries before the least-recently-used ones are
     *                    evicted (must be {@code > 0})
     * @return a new cache instance
     */
    public static <K, V> Cache<K, V> create(int maximumSize)
    {
        return new Cache<>(Caffeine.newBuilder().maximumSize(requirePositive(maximumSize, "maximumSize")).build());
    }

    /**
     * Creates a cache bounded by size with write expiry.
     *
     * <p>Suitable for cached copies of durable data that must periodically refresh from the
     * database.
     *
     * @param maximumSize the maximum number of entries before the least-recently-used ones are
     *                    evicted (must be {@code > 0})
     * @param expiry the time after which an entry is considered expired (must be {@code > 0})
     * @param unit the unit of the expiry duration
     * @return a new cache instance
     */
    public static <K, V> Cache<K, V> create(int maximumSize, long expiry, TimeUnit unit)
    {
        if (expiry <= 0)
        {
            throw new IllegalArgumentException("expiry must be > 0");
        }
        Objects.requireNonNull(unit, "unit must not be null");
        return new Cache<>(Caffeine.newBuilder()
                .maximumSize(requirePositive(maximumSize, "maximumSize"))
                .expireAfterWrite(expiry, unit)
                .build());
    }

    /**
     * Returns the value for the given key, or a newly loaded and cached value.
     *
     * <p>When the key is absent, the loader is invoked atomically: concurrent calls for the same
     * key share a single load, so the database (or any other source) is hit at most once per
     * missing key. A loader returning {@code null} caches the key as absent (see class javadoc);
     * a loader that throws is not cached and the exception propagates to the caller.
     *
     * @param key the key to look up
     * @param loader the function producing a value for a missing key
     * @return the cached value, the freshly loaded value, or {@code null} when the loader found
     *         nothing
     */
    @SuppressWarnings("unchecked")
    public V get(K key, Function<? super K, ? extends V> loader)
    {
        K checkedKey = Objects.requireNonNull(key, "key must not be null");
        Object value = this.cache.get(checkedKey, k ->
        {
            V loaded = loader.apply(k);
            return loaded == null ? ABSENT : loaded;
        });
        return value == ABSENT ? null : (V) value;
    }

    /**
     * Returns the value for the given key, or {@code null} when absent (never loading).
     *
     * @param key the key to look up
     * @return the cached value, or {@code null} when not present
     */
    @SuppressWarnings("unchecked")
    public V getIfPresent(K key)
    {
        Object value = this.cache.getIfPresent(key);
        return value == ABSENT ? null : (V) value;
    }

    /**
     * Associates the given value with the given key, replacing any previous value.
     *
     * <p>Passing {@code null} removes the entry, overriding a cached absence; passing a non-null
     * value replaces any cached absence for that key.
     *
     * @param key the key
     * @param value the value, may be {@code null} to remove the entry
     */
    public void put(K key, V value)
    {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null)
        {
            this.cache.invalidate(key);
        }
        else
        {
            this.cache.put(key, value);
        }
    }

    /**
     * Removes the entry for the given key, if any.
     *
     * @param key the key
     */
    public void remove(K key)
    {
        this.cache.invalidate(key);
    }

    /**
     * Removes every entry whose key matches the predicate, such as all keys under a prefix.
     *
     * @param predicate selects the keys to remove
     */
    public void removeIf(Predicate<? super K> predicate)
    {
        Objects.requireNonNull(predicate, "predicate must not be null");
        this.cache.asMap().keySet().removeIf(predicate);
    }

    /**
     * Removes all entries from the cache.
     */
    public void clear()
    {
        this.cache.invalidateAll();
    }

    /**
     * Validates that the provided value is positive. If the value is not greater than zero,
     * an {@link IllegalArgumentException} is thrown with a message containing the provided name.
     *
     * @param value the value to check (must be > 0)
     * @param name the name of the parameter used in the exception message if validation fails
     * @return the validated value if it is positive
     * @throws IllegalArgumentException if the value is not greater than zero
     */
    private static int requirePositive(int value, String name)
    {
        if (value <= 0)
        {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }
}