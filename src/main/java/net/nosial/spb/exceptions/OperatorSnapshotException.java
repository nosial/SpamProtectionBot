package net.nosial.spb.exceptions;

import net.nosial.spb.exceptions.DatabaseException;

import java.io.Serial;

/**
 * Carries a {@link DatabaseException} through the cache loader, whose functional signature
 * cannot throw checked exceptions. The original cause is rethrown by listOperators();
 * because the loader throws, the failed snapshot is never cached, so the next poll retries.
 */
public final class OperatorSnapshotException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final DatabaseException cause;

    /**
     * Wraps a database failure so it can pass through a cache loader.
     *
     * @param cause the underlying database failure
     */
    public OperatorSnapshotException(DatabaseException cause)
    {
        this.cause = cause;
    }

    /**
     * Retrieves the underlying {@link DatabaseException} that caused this exception.
     *
     * @return the {@link DatabaseException} that was the original cause of this exception
     */
    public DatabaseException cause()
    {
        return this.cause;
    }
}
