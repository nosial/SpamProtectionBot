package net.nosial.spb.classes.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.objects.BotIdentity;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

/**
 * The bot's connection to Telegram, as everything downstream of it sees it.
 *
 * <p>Handlers need three things from the connection — a client to answer with, the mapper it
 * serialises through, and who the bot is. They do not need to know how it was established, which
 * is the whole of {@link net.nosial.spb.classes.TelegramBot}. Naming the three means the rest of
 * the bot can be assembled and tested without opening a socket.
 */
public interface TelegramConnection
{
    /**
     * Returns the client calls are made through.
     *
     * @return the Telegram API client
     */
    OkHttpTelegramClient client();

    /**
     * Returns the mapper the client serialises with, used for diagnostic logging.
     *
     * @return the object mapper
     */
    ObjectMapper objectMapper();

    /**
     * Returns who Telegram says the bot is.
     *
     * @return the bot's identity, or {@code null} before it has authenticated
     */
    BotIdentity identity();
}
