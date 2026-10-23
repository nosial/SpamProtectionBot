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

    @Test
    void explicitEntityDoesNotRequireSupportingReport() throws Exception
    {
        var context = net.nosial.spb.support.Contexts.template();
        var message = net.nosial.spb.support.Updates.message(1, 42L, "private", "/blacklist").getMessage();
        var parsed = BlacklistHandler.parseArguments(context, message,
                new String[]{"+14165551234@e164.arpa", "tracking", "30d"}, context.languages().defaultLanguage());
        assertEquals("+14165551234@e164.arpa", parsed.entityIdentifier());
        assertEquals(net.nosial.jfederation.enums.IncidentType.TRACKING, parsed.incidentType());
        assertEquals(2592000L, parsed.expirationSeconds());
        assertNull(parsed.reportUuid());
        assertNull(parsed.targetAuthor());
    }

    @Test
    void explicitSupportingReportRemainsSupported() throws Exception
    {
        var context = net.nosial.spb.support.Contexts.template();
        var message = net.nosial.spb.support.Updates.message(1, 42L, "private", "/blacklist").getMessage();
        String report = "123e4567-e89b-12d3-a456-426614174000";
        var parsed = BlacklistHandler.parseArguments(context, message,
                new String[]{"42", report, "advertising", "12h"}, context.languages().defaultLanguage());
        assertEquals("42@telegram.org", parsed.entityIdentifier());
        assertEquals(report, parsed.reportUuid());
        assertEquals(net.nosial.jfederation.enums.IncidentType.ADVERTISING, parsed.incidentType());
    }
}
