package net.nosial.spb.objects;

import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for what a handler can read off the context it is given.
 */
class HandlerContextTest
{
    /**
     * Binds an update to a test context.
     *
     * @param update the update to bind
     * @return the per-update context
     */
    private static HandlerContext contextFor(Update update)
    {
        return Contexts.template().withUpdate(update);
    }

    @Nested
    @DisplayName("Deriving per-update contexts")
    class Derivation
    {
        @Test
        @DisplayName("the derived context carries the update and shares every service")
        void sharesServices()
        {
            HandlerContext template = Contexts.template();
            Update update = Updates.privateMessage(1, "hello");

            HandlerContext derived = template.withUpdate(update);

            assertNotSame(template, derived);
            assertSame(update, derived.update());
            assertSame(template.cache(), derived.cache());
            assertSame(template.bot(), derived.bot());
            assertSame(template.objectMapper(), derived.objectMapper());
            assertNull(template.update());
        }
    }

    @Nested
    @DisplayName("Finding the message")
    class Messages
    {
        @Test
        @DisplayName("a message is found wherever it arrived")
        void findsTheMessage()
        {
            assertEquals("hello", contextFor(Updates.privateMessage(1, "hello")).message().getText());
            assertEquals("edited", contextFor(Updates.editedMessage(2, "edited")).message().getText());
            assertEquals("post", contextFor(Updates.channelPost(3, "post")).message().getText());
        }

        @Test
        @DisplayName("a button press exposes the message it was attached to")
        void findsTheCallbackMessage()
        {
            assertEquals("menu", contextFor(Updates.callbackQuery(1, "demo:open")).message().getText());
        }

        @Test
        @DisplayName("an update carrying no message says so")
        void reportsNoMessage()
        {
            assertNull(contextFor(Updates.poll(1)).message());
            assertNull(Contexts.template().message());
        }

        @Test
        @DisplayName("the chat and author are read off the message")
        void readsChatAndAuthor()
        {
            HandlerContext context = contextFor(Updates.groupMessage(1, "hello"));

            assertEquals(-1001234567890L, context.chatId());
            assertEquals(42L, context.userId());
            assertNotNull(context.user());
        }

        @Test
        @DisplayName("a channel post has no author")
        void channelPostsHaveNoAuthor()
        {
            HandlerContext context = contextFor(Updates.channelPost(1, "post"));

            assertNull(context.userId());
            assertEquals(-1009876543210L, context.chatId());
        }
    }

    @Nested
    @DisplayName("Reading commands")
    class Commands
    {
        @Test
        @DisplayName("the command name is lowercased and stripped of its decorations")
        void readsTheCommandName()
        {
            assertEquals("start", contextFor(Updates.privateMessage(1, "/start")).commandName());
            assertEquals("start", contextFor(Updates.privateMessage(2, "/start@testbot")).commandName());
            assertEquals("start", contextFor(Updates.privateMessage(3, "/START payload")).commandName());
            assertEquals("start", contextFor(Updates.privateMessage(4, "  /start  ")).commandName());
        }

        @Test
        @DisplayName("text that is not a command has no command name")
        void readsNoNameFromPlainText()
        {
            assertNull(contextFor(Updates.privateMessage(1, "hello")).commandName());
            assertNull(contextFor(Updates.privateMessage(2, "/")).commandName());
            assertNull(contextFor(Updates.privateMessage(3, null)).commandName());
            assertNull(contextFor(Updates.callbackQuery(4, "demo:open")).commandName());
        }

        @Test
        @DisplayName("the payload is whatever follows the command token")
        void readsThePayload()
        {
            assertEquals("payload", contextFor(Updates.privateMessage(1, "/start payload")).commandPayload());
            assertEquals("two words", contextFor(Updates.privateMessage(2, "/start two words")).commandPayload());
            assertEquals("payload", contextFor(Updates.privateMessage(3, "/start@testbot payload")).commandPayload());
        }

        @Test
        @DisplayName("a command without a payload has none")
        void readsNoPayloadWhenThereIsNone()
        {
            assertNull(contextFor(Updates.privateMessage(1, "/start")).commandPayload());
            assertNull(contextFor(Updates.privateMessage(2, "/start   ")).commandPayload());
        }
    }

    @Nested
    @DisplayName("Reading button presses")
    class Callbacks
    {
        @Test
        @DisplayName("the callback query and its data are exposed")
        void readsTheCallback()
        {
            HandlerContext context = contextFor(Updates.callbackQuery(1, "demo:open"));

            assertNotNull(context.callbackQuery());
            assertEquals("demo:open", context.callbackData());
            assertEquals(42L, context.userId());
        }

        @Test
        @DisplayName("an update that is not a button press has no callback data")
        void readsNoCallbackFromOtherUpdates()
        {
            assertNull(contextFor(Updates.privateMessage(1, "hello")).callbackQuery());
            assertNull(contextFor(Updates.privateMessage(2, "hello")).callbackData());
        }
    }
}
