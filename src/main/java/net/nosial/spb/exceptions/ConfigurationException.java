package net.nosial.spb.exceptions;

import java.io.Serial;

/**
 * Thrown when the configuration file cannot be read, parsed, or validated.
 */
public class ConfigurationException extends Exception
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new configuration exception with the given message.
     *
     * @param message the error description
     */
    public ConfigurationException(String message)
    {
        super(message);
    }

    /**
     * Creates a new configuration exception with the given message and cause.
     *
     * @param message the error description
     * @param cause the underlying cause
     */
    public ConfigurationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
