package net.nosial.spb.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.classes.interfaces.TelegramConnection;
import net.nosial.spb.objects.BotIdentity;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

/**
 * A Telegram connection that never opens one.
 *
 * <p>Most handler logic — deciding what an update is, whether to claim it, what to say — happens
 * before anything is sent. Tests of that logic need the bot's identity and nothing else, so this
 * supplies an identity and leaves the client {@code null}: a test that unexpectedly tries to send
 * something fails loudly rather than quietly talking to a mock.
 */
public final class StubTelegramConnection implements TelegramConnection
{
    private final BotIdentity identity;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a connection reporting the given identity.
     *
     * @param identity who the bot should appear to be
     */
    public StubTelegramConnection(BotIdentity identity)
    {
        this.identity = identity;
    }

    @Override
    public OkHttpTelegramClient client()
    {
        return null;
    }

    @Override
    public ObjectMapper objectMapper()
    {
        return this.objectMapper;
    }

    @Override
    public BotIdentity identity()
    {
        return this.identity;
    }
}
