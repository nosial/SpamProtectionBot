package net.nosial.spb.classes.interfaces;

import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * A send interaction that has been built but not yet made.
 *
 * <p>Echoing a flagged message back to a business owner means choosing between a dozen send
 * methods by attachment type, then executing whichever was chosen through one place that logs it
 * and handles the failure. This is what lets that choice be made separately from the execution.
 *
 * <p>It exists rather than {@link java.util.concurrent.Callable} because it throws exactly
 * {@link TelegramApiException}: with {@code Callable} the caller would have to catch
 * {@link Exception} and would swallow failures that have nothing to do with Telegram.
 */
@FunctionalInterface
public interface EchoCall
{
    /**
     * Executes the send.
     *
     * @return the sent message
     * @throws TelegramApiException if Telegram rejects it
     */
    Message send() throws TelegramApiException;
}
