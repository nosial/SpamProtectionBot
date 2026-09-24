package net.nosial.spb.exceptions;

import java.io.Serial;

/**
 * Thrown when the command line cannot be parsed into a usable set of options.
 */
public class CommandLineException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new command-line exception with the given message.
     *
     * @param message the error description
     */
    public CommandLineException(String message)
    {
        super(message);
    }

    /**
     * Creates a new command-line exception with the given message and cause.
     *
     * @param message the error description
     * @param cause the underlying cause
     */
    public CommandLineException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
