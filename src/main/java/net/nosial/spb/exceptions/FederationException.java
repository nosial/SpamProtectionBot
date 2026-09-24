package net.nosial.spb.exceptions;

import java.io.Serial;

/**
 * Thrown when a Federation operation cannot be completed.
 *
 * <p>Covers both "no server is configured" and "the configured server refused or could not be
 * reached". Handlers treat either the same way: tell the user the feature is unavailable and carry
 * on, because Federation being down must never stop the bot from working.
 */
public class FederationException extends Exception
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new federation exception with the given message.
     *
     * @param message the error description
     */
    public FederationException(String message)
    {
        super(message);
    }

    /**
     * Creates a new federation exception with the given message and cause.
     *
     * @param message the error description
     * @param cause the underlying cause
     */
    public FederationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
