package net.nosial.spb.handlers.secretary;

import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.sessions.SecretaryReportStore;
import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.handlers.secretary.SecretaryMessageHandler.ContactDecision;
import net.nosial.spb.objects.SecretaryReportTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecretaryReportButtonTest
{
    @Test
    @DisplayName("allowing or denying a contact keeps the notification's Report button")
    void decisionKeepsReportButton()
    {
        LanguageManager languages = new LanguageManager("en");
        InlineKeyboardButton report = InlineKeyboardButton.builder().text("Report Message")
                .callbackData(SecretarySettingsHandler.REPORT_CALLBACK_PREFIX + ":token").build();
        InlineKeyboardMarkup original = SecretaryMessageHandler.contactDecisionMarkup(languages,
                new ContactDecision("conn", 42, languages.defaultLanguage(), SecretaryContactStatus.ALLOWED));
        List<InlineKeyboardRow> rows = new ArrayList<>(original.getKeyboard());
        rows.add(new InlineKeyboardRow(report));

        InlineKeyboardMarkup denied = SecretaryMessageHandler.keepReportButton(
                SecretaryMessageHandler.contactDecisionMarkup(languages,
                        new ContactDecision("conn", 42, languages.defaultLanguage(), SecretaryContactStatus.DENIED)),
                InlineKeyboardMarkup.builder().keyboard(rows).build());

        assertEquals(2, denied.getKeyboard().size());
        assertEquals(report, denied.getKeyboard().get(1).get(0));
    }

    @Test
    @DisplayName("a Report token only resolves for the owner it was issued to")
    void tokenIsBoundToOwner()
    {
        SecretaryReportStore store = new SecretaryReportStore();
        String token = store.store(new SecretaryReportTarget(1, 2, 3, "spam", List.of(), Map.of()));

        assertNotNull(store.find(token, 1));
        assertNull(store.find(token, 2));
    }
}
