package net.nosial.spb.classes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link FederationWebLinks}.
 */
class FederationWebLinksTest
{
    private static final String UUID = "0199a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b";

    @Test
    @DisplayName("the endpoint resolves to the same record URL with or without a trailing slash")
    void normalizesTrailingSlash()
    {
        String expected = "https://federation.example.com/reports/" + UUID;

        assertEquals(expected, new FederationWebLinks("https://federation.example.com/").url(FederationWebLinks.Record.REPORT, UUID));
        assertEquals(expected, new FederationWebLinks("https://federation.example.com").url(FederationWebLinks.Record.REPORT, UUID));
        assertEquals("https://example.com/federation/entities/" + UUID,
                new FederationWebLinks("https://example.com/federation").url(FederationWebLinks.Record.ENTITY, UUID));
    }

    @Test
    @DisplayName("no link is produced when no web application is configured")
    void disabledProducesNothing()
    {
        assertNull(FederationWebLinks.DISABLED.url(FederationWebLinks.Record.EVIDENCE, UUID));
        assertNull(FederationWebLinks.DISABLED.button(new LanguageManager("en"), null, FederationWebLinks.Record.EVIDENCE, UUID));
    }

    @Test
    @DisplayName("clearing used-up actions keeps the link to the web application")
    void linksOnlyKeepsLinkButtons()
    {
        InlineKeyboardButton action = InlineKeyboardButton.builder().text("Close").callbackData("close").build();
        InlineKeyboardButton link = InlineKeyboardButton.builder().text("View Report").url("https://federation.example.com/reports/" + UUID).build();
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(action))
                .keyboardRow(new InlineKeyboardRow(link))
                .build();

        assertEquals(List.of(new InlineKeyboardRow(link)), FederationWebLinks.linksOnly(markup).getKeyboard());
    }
}
