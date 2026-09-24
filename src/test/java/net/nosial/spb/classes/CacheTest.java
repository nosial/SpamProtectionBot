package net.nosial.spb.classes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for telling a load that found nothing apart from a load that failed.
 */
class CacheTest
{
    private final Cache<Long, String> cache = Cache.create(100, 10, TimeUnit.MINUTES);

    @Test
    @DisplayName("a failed load returns the fallback and is retried on the next lookup")
    void failedLoadIsNotCached()
    {
        AtomicInteger loads = new AtomicInteger();

        String first = this.cache.get(1L, key ->
        {
            loads.incrementAndGet();
            throw new Cache.LoadFailedException("source down", new IllegalStateException());
        }, "fallback");
        String second = this.cache.get(1L, key ->
        {
            loads.incrementAndGet();
            return "loaded";
        }, "fallback");

        assertEquals("fallback", first);
        assertEquals("loaded", second, "the failure must not have been remembered");
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("a load that finds nothing is still cached as absent")
    void absenceIsCached()
    {
        AtomicInteger loads = new AtomicInteger();

        assertNull(this.cache.get(1L, key ->
        {
            loads.incrementAndGet();
            return null;
        }, "fallback"));
        assertNull(this.cache.get(1L, key ->
        {
            loads.incrementAndGet();
            return "loaded";
        }, "fallback"));

        assertEquals(1, loads.get(), "a genuine absence is served from the cache");
    }
}
