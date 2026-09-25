package net.nosial.spb.handlers.operators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests how {@link BlacklistHandler} recognises the Telegram entities it publishes before blacklisting.
 */
class BlacklistHandlerTest
{
    @Test
    @DisplayName("user and chat addresses yield their Telegram ids")
    void telegramAddressesAreRecognised()
    {
        assertEquals(8940919058L, BlacklistHandler.telegramId("8940919058@telegram.org"));
        assertEquals(-1004001234567L, BlacklistHandler.telegramId("-1004001234567@telegram.org"));
    }

    @Test
    @DisplayName("other identifiers are left to the server")
    void otherIdentifiersAreIgnored()
    {
        assertNull(BlacklistHandler.telegramId("123e4567-e89b-12d3-a456-426614174000"));
        assertNull(BlacklistHandler.telegramId("someone@example.com"));
        assertNull(BlacklistHandler.telegramId("@username@telegram.org"));
    }
}
