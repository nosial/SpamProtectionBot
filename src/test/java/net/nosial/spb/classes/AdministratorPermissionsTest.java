package net.nosial.spb.classes;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that each administrator check asks for the permission it names.
 *
 * <p>The cached list holds every administrator. The settings menu needs Change Group Information,
 * while moderation needs the right to delete messages or restrict members, and neither implies the
 * other.
 */
class AdministratorPermissionsTest
{
    private static final long CHAT = -1001234567890L;
    private static final long INFO_ONLY = 1L;
    private static final long DELETE_ONLY = 2L;
    private static final long OWNER = 3L;

    @TempDir
    Path directory;

    /**
     * Returns a context whose cached administrator list for {@link #CHAT} holds the bot and the
     * test's administrators.
     *
     * @param botCanChangeInfo whether the bot holds Change Group Information
     * @param botCanDelete whether the bot can delete messages
     * @return the context
     */
    private HandlerContext contextWith(boolean botCanChangeInfo, boolean botCanDelete)
    {
        HandlerContext context = Contexts.template(this.directory);
        context.chatAdmins().put(CHAT, List.of(
                new AdminInfo(Contexts.BOT.id(), false, botCanDelete, false, botCanChangeInfo),
                new AdminInfo(INFO_ONLY, false, false, false, true),
                new AdminInfo(DELETE_ONLY, false, true, false, false),
                new AdminInfo(OWNER, true, true, true, true)));
        return context;
    }

    /**
     * Builds a group message from the given user in {@link #CHAT}.
     *
     * @param userId the sender
     * @return the message
     */
    private static Message messageFrom(long userId)
    {
        return Updates.fromJson("""
                {"update_id":1,"message":{"message_id":1,"date":1700000000,
                 "chat":{"id":%d,"type":"supergroup","title":"Test Chat"},
                 "from":{"id":%d,"is_bot":false,"first_name":"Tester"},"text":"/settings"}}"""
                .formatted(CHAT, userId)).getMessage();
    }

    @Test
    @DisplayName("a bot with only Change Group Information may open the settings menu")
    void botWithOnlyChangeInfoIsAccepted()
    {
        // The permission the bot asks for is Change Group Information; Delete Messages and Ban
        // Users are optional. The bot used to be left out of the cache without one of those two.
        assertTrue(Handler.isBotChangeInformationAdministrator(contextWith(true, false), CHAT));
    }

    @Test
    @DisplayName("a bot without Change Group Information may not, whatever else it can do")
    void botWithoutChangeInfoIsRefused()
    {
        assertFalse(Handler.isBotChangeInformationAdministrator(contextWith(false, true), CHAT));
    }

    @Test
    @DisplayName("the settings menu follows Change Group Information, not moderation rights")
    void settingsFollowChangeInfo()
    {
        HandlerContext context = contextWith(true, true);

        assertTrue(Handler.isChangeInformationAdministrator(context, messageFrom(INFO_ONLY)));
        assertTrue(Handler.isChangeInformationAdministrator(context, messageFrom(OWNER)));
        assertFalse(Handler.isChangeInformationAdministrator(context, messageFrom(DELETE_ONLY)));
    }

    @Test
    @DisplayName("moderation follows the right to delete or restrict, not Change Group Information")
    void moderationFollowsModerationRights()
    {
        HandlerContext context = contextWith(true, true);

        assertTrue(Handler.isChatAdministrator(context, CHAT, DELETE_ONLY));
        assertTrue(Handler.isChatAdministrator(context, CHAT, OWNER));
        assertFalse(Handler.isChatAdministrator(context, CHAT, INFO_ONLY));
    }

    @Test
    @DisplayName("every update type a handler can use is requested from Telegram")
    void everyHandledUpdateTypeIsRequested()
    {
        // Telegram only sends chat_member updates when they are asked for by name; without them
        // join protection misses joins in chats that hide join messages.
        Set<UpdateType> notRequested = EnumSet.of(UpdateType.ANY, UpdateType.COMMAND,
                UpdateType.CHOSEN_INLINE_QUERY, UpdateType.MESSAGE_REACTION, UpdateType.MESSAGE_REACTION_COUNT);

        for (UpdateType type : UpdateType.values())
        {
            if (!notRequested.contains(type))
            {
                assertTrue(TelegramBot.ALLOWED_UPDATES.contains(type.name().toLowerCase()),
                        type + " is not requested from Telegram");
            }
        }
        assertTrue(TelegramBot.ALLOWED_UPDATES.contains("chosen_inline_result"));
    }
}
