package net.nosial.spb.enums;

import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for recognising the kind of an incoming update.
 */
class UpdateTypeTest
{
    @Test
    @DisplayName("ANY matches every update")
    void anyMatchesEverything()
    {
        assertTrue(UpdateType.ANY.matches(Updates.privateMessage(1, "hello")));
        assertTrue(UpdateType.ANY.matches(Updates.callbackQuery(2, "data")));
        assertTrue(UpdateType.ANY.matches(Updates.poll(3)));
    }

    @Test
    @DisplayName("a null update matches nothing")
    void nullMatchesNothing()
    {
        assertFalse(UpdateType.ANY.matches(null));
        assertFalse(UpdateType.MESSAGE.matches(null));
        assertNull(UpdateType.of(null));
    }

    @Test
    @DisplayName("MESSAGE matches a plain message but not a callback query")
    void messageMatchesMessages()
    {
        assertTrue(UpdateType.MESSAGE.matches(Updates.privateMessage(1, "hello")));
        assertFalse(UpdateType.MESSAGE.matches(Updates.callbackQuery(2, "data")));
        assertFalse(UpdateType.MESSAGE.matches(Updates.channelPost(3, "post")));
    }

    @Test
    @DisplayName("COMMAND matches only a message whose first token is a command")
    void commandMatchesCommands()
    {
        assertTrue(UpdateType.COMMAND.matches(Updates.privateMessage(1, "/start")));
        assertTrue(UpdateType.COMMAND.matches(Updates.privateMessage(2, "/start@bot with payload")));
        assertFalse(UpdateType.COMMAND.matches(Updates.privateMessage(3, "not a command")));
        assertFalse(UpdateType.COMMAND.matches(Updates.privateMessage(4, "text /start in the middle")));
        assertFalse(UpdateType.COMMAND.matches(Updates.privateMessage(5, "/ ")));
        assertFalse(UpdateType.COMMAND.matches(Updates.privateMessage(6, null)));
    }

    @Test
    @DisplayName("a command is also a message")
    void commandIsAlsoAMessage()
    {
        Update update = Updates.privateMessage(1, "/start");

        assertTrue(UpdateType.MESSAGE.matches(update));
        assertTrue(UpdateType.COMMAND.matches(update));
    }

    @Test
    @DisplayName("each remaining type matches only its own update")
    void otherTypesMatchTheirOwn()
    {
        assertTrue(UpdateType.CALLBACK_QUERY.matches(Updates.callbackQuery(1, "data")));
        assertTrue(UpdateType.EDITED_MESSAGE.matches(Updates.editedMessage(2, "edited")));
        assertTrue(UpdateType.CHANNEL_POST.matches(Updates.channelPost(3, "post")));
        assertTrue(UpdateType.POLL.matches(Updates.poll(4)));

        assertFalse(UpdateType.CALLBACK_QUERY.matches(Updates.privateMessage(5, "hello")));
        assertFalse(UpdateType.EDITED_MESSAGE.matches(Updates.privateMessage(6, "hello")));
    }

    @Test
    @DisplayName("the most specific type is resolved, with COMMAND ahead of MESSAGE")
    void resolvesTheMostSpecificType()
    {
        assertEquals(UpdateType.COMMAND, UpdateType.of(Updates.privateMessage(1, "/start")));
        assertEquals(UpdateType.MESSAGE, UpdateType.of(Updates.privateMessage(2, "hello")));
        assertEquals(UpdateType.CALLBACK_QUERY, UpdateType.of(Updates.callbackQuery(3, "data")));
        assertEquals(UpdateType.CHANNEL_POST, UpdateType.of(Updates.channelPost(4, "post")));
    }

    @Test
    @DisplayName("a type carrying a message hands it over")
    void extractsTheCarriedMessage()
    {
        assertNotNull(UpdateType.MESSAGE.messageOf(Updates.privateMessage(1, "hello")));
        assertEquals("edited", UpdateType.EDITED_MESSAGE.messageOf(Updates.editedMessage(2, "edited")).getText());
        assertEquals("post", UpdateType.CHANNEL_POST.messageOf(Updates.channelPost(3, "post")).getText());
    }

    @Test
    @DisplayName("a type carries no message when it does not apply")
    void extractsNothingWhenItDoesNotApply()
    {
        assertNull(UpdateType.MESSAGE.messageOf(Updates.callbackQuery(1, "data")));
        assertNull(UpdateType.ANY.messageOf(Updates.privateMessage(2, "hello")));
        assertNull(UpdateType.MESSAGE.messageOf(null));
    }
}
