package net.nosial.spb.exceptions;


import java.io.Serial;

/**
 * Carries an argument-validation failure together with the user-facing message to send.
 */
public final class ArgumentParseException extends Exception
{
    @Serial
    private static final long serialVersionUID = 1L;
    private final String message;

    /**
     * Creates the failure with the message to show the user.
     *
     * @param message the user-facing explanation
     */
    public ArgumentParseException(String message)
    {
        this.message = message;
    }

    /**
     * Retrieves the message associated with this exception.
     *
     * @return the user-facing explanation of the validation failure
     */
    public String message()
    {
        return this.message;
    }
}
