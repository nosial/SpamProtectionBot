package net.nosial.spb.exceptions;

import java.io.Serial;

/**
 * Thrown when the SQLite database cannot be opened, initialised, or accessed.
 */
public class DatabaseException extends Exception
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new database exception with the given message.
     *
     * @param message the error description
     */
    public DatabaseException(String message)
    {
        super(message);
    }

    /**
     * Creates a new database exception with the given message and cause.
     *
     * @param message the error description
     * @param cause the underlying cause
     */
    public DatabaseException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
