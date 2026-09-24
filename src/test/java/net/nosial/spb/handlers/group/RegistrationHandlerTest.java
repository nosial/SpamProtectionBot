package net.nosial.spb.handlers.group;

import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the bookkeeping {@link RegistrationHandler} keeps current when a chat changes under it.
 */
class RegistrationHandlerTest
{
    private static final long BASIC_GROUP = -4001234567L;
    private static final long SUPERGROUP = -1004001234567L;
    private static final List<AdminInfo> STALE_ADMINS = List.of(new AdminInfo(42L, true, true, true, true));

    @TempDir
    Path directory;

    /**
     * Runs the handler over the given update JSON.
     *
     * @param context the context to run in
     * @param json the update
     */
    private static void handle(HandlerContext context, String json)
    {
        new RegistrationHandler().handle(context.withUpdate(Updates.fromJson(json)));
    }

    /**
     * Builds a membership update for the given member in the given chat.
     *
     * @param updateKey {@code my_chat_member} or {@code chat_member}
     * @param userId the member whose status changed
     * @param oldStatus the member's previous status
     * @param newStatus the member's new status
     * @return the update JSON
     */
    private static String membership(String updateKey, long userId, String oldStatus, String newStatus)
    {
        return """
                {"update_id":1,"%s":{"date":1700000000,
                 "chat":{"id":%d,"type":"group","title":"Test Chat"},
                 "from":{"id":42,"is_bot":false,"first_name":"Owner"},
                 "old_chat_member":%s,
                 "new_chat_member":%s}}"""
                .formatted(updateKey, BASIC_GROUP, member(userId, oldStatus), member(userId, newStatus));
    }

    /**
     * Builds a chat member JSON object with the given status.
     *
     * @param userId the member
     * @param status the member's status
     * @return the member JSON
     */
    private static String member(long userId, String status)
    {
        String user = "{\"id\":%d,\"is_bot\":false,\"first_name\":\"Member\"}".formatted(userId);
        if (status.equals("administrator"))
        {
            return """
                    {"status":"administrator","user":%s,"can_be_edited":false,"is_anonymous":false,
                     "can_manage_chat":true,"can_delete_messages":false,"can_manage_video_chats":false,
                     "can_restrict_members":false,"can_promote_members":false,"can_change_info":true,
                     "can_invite_users":true,"can_post_stories":false,"can_edit_stories":false,
                     "can_delete_stories":false}""".formatted(user);
        }
        return "{\"status\":\"%s\",\"user\":%s}".formatted(status, user);
    }

    @Nested
    @DisplayName("Keeping the administrator list current")
    class Administrators
    {
        @Test
        @DisplayName("promoting the bot drops the chat's cached administrator list")
        void botPromotionDropsCache()
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            context.chatAdmins().put(BASIC_GROUP, STALE_ADMINS);

            handle(context, membership("my_chat_member", Contexts.BOT.id(), "member", "administrator"));

            assertNull(context.chatAdmins().getIfPresent(BASIC_GROUP),
                    "the list cached before the promotion must not outlive it");
        }

        @Test
        @DisplayName("demoting another administrator drops the chat's cached administrator list")
        void demotionDropsCache()
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            context.chatAdmins().put(BASIC_GROUP, STALE_ADMINS);

            handle(context, membership("chat_member", 77L, "administrator", "member"));

            assertNull(context.chatAdmins().getIfPresent(BASIC_GROUP));
        }

        @Test
        @DisplayName("an ordinary member joining leaves the cached administrator list alone")
        void ordinaryJoinKeepsCache()
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            context.chatAdmins().put(BASIC_GROUP, STALE_ADMINS);

            handle(context, membership("chat_member", 77L, "left", "member"));

            assertEquals(STALE_ADMINS, context.chatAdmins().getIfPresent(BASIC_GROUP));
        }
    }

    @Nested
    @DisplayName("Following a basic group upgraded to a supergroup")
    class Migration
    {
        /**
         * Builds a service message announcing a migration.
         *
         * @param chatId the chat the message is posted in
         * @param chatType the chat's type
         * @param field {@code migrate_to_chat_id} or {@code migrate_from_chat_id}
         * @param otherChatId the chat id the field names
         * @return the update JSON
         */
        private static String migration(long chatId, String chatType, String field, long otherChatId)
        {
            return """
                    {"update_id":1,"message":{"message_id":7,"date":1700000000,
                     "chat":{"id":%d,"type":"%s","title":"Test Chat"},
                     "from":{"id":42,"is_bot":false,"first_name":"Owner"},
                     "%s":%d}}""".formatted(chatId, chatType, field, otherChatId);
        }

        @Test
        @DisplayName("the configuration and language follow the group to its new id")
        void configurationFollowsMigrateTo() throws Exception
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            Language spanish = Contexts.languages().resolve("es");
            context.managers().chatConfigurations().enableChatConfiguration(BASIC_GROUP);
            context.managers().chatConfigurations().setScanningEnabled(BASIC_GROUP, false);
            context.managers().languagePreferences().setChatLanguage(BASIC_GROUP, spanish);

            handle(context, migration(BASIC_GROUP, "group", "migrate_to_chat_id", SUPERGROUP));

            var moved = context.managers().chatConfigurations().getChatConfiguration(SUPERGROUP);
            assertTrue(moved.isPresent(), "the supergroup should carry the group's configuration");
            assertTrue(moved.get().enabled());
            assertFalse(moved.get().scanningEnabled(), "customised settings move too");
            assertEquals(spanish, context.managers().languagePreferences().getChatLanguage(SUPERGROUP));
            assertFalse(context.managers().chatConfigurations().chatConfigurationExists(BASIC_GROUP));
        }

        @Test
        @DisplayName("the announcement in the new supergroup moves it just the same")
        void configurationFollowsMigrateFrom() throws Exception
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            context.managers().chatConfigurations().enableChatConfiguration(BASIC_GROUP);

            handle(context, migration(SUPERGROUP, "supergroup", "migrate_from_chat_id", BASIC_GROUP));

            assertTrue(context.managers().chatConfigurations().resolve(SUPERGROUP).enabled());
        }

        @Test
        @DisplayName("both announcements together move it once and overwrite nothing")
        void migrationIsIdempotent() throws Exception
        {
            HandlerContext context = Contexts.template(RegistrationHandlerTest.this.directory);
            context.managers().chatConfigurations().enableChatConfiguration(BASIC_GROUP);

            handle(context, migration(BASIC_GROUP, "group", "migrate_to_chat_id", SUPERGROUP));
            context.managers().chatConfigurations().setScanningEnabled(SUPERGROUP, false);
            handle(context, migration(SUPERGROUP, "supergroup", "migrate_from_chat_id", BASIC_GROUP));

            var configuration = context.managers().chatConfigurations().getChatConfiguration(SUPERGROUP);
            assertNotNull(configuration.orElse(null));
            assertFalse(configuration.get().scanningEnabled(), "a later change on the new id survives");
        }
    }
}
