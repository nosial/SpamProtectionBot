package net.nosial.spb.exceptions;

import java.io.Serial;

/**
 * Thrown when the annotated handler layer cannot be discovered or instantiated.
 *
 * <p>This signals a programming error in a handler declaration (a missing public no-argument
 * constructor, an abstract class carrying the annotation, a class that does not extend the handler
 * base type, ...) rather than a runtime failure while processing an update, which is logged and
 * swallowed by the dispatcher instead.
 */
public class HandlerException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new handler exception with the given message.
     *
     * @param message the error description
     */
    public HandlerException(String message)
    {
        super(message);
    }

    /**
     * Creates a new handler exception with the given message and cause.
     *
     * @param message the error description
     * @param cause the underlying cause
     */
    public HandlerException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
