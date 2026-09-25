package net.nosial.spb.handlers.group;

import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests that {@link BotMembershipHandler} stays quiet for membership changes that need no guidance,
 * since the bot's permissions are edited far more often than it joins.
 */
class BotMembershipHandlerTest
{
    private static final long GROUP = -1004001234567L;

    @TempDir
    Path directory;

    /**
     * Builds the bot's administrator state.
     *
     * @param canChangeInfo whether the bot may change group information
     * @param canDelete whether the bot may delete messages
     * @return the member JSON
     */
    private static String administrator(boolean canChangeInfo, boolean canDelete)
    {
        return """
                {"status":"administrator","user":{"id":%d,"is_bot":true,"first_name":"Test Bot"},
                 "can_be_edited":false,"is_anonymous":false,"can_manage_chat":true,
                 "can_delete_messages":%b,"can_manage_video_chats":false,"can_restrict_members":false,
                 "can_promote_members":false,"can_change_info":%b,"can_invite_users":true,
                 "can_post_stories":false,"can_edit_stories":false,"can_delete_stories":false}"""
                .formatted(Contexts.BOT.id(), canDelete, canChangeInfo);
    }

    private static String left()
    {
        return "{\"status\":\"left\",\"user\":{\"id\":%d,\"is_bot\":true,\"first_name\":\"Test Bot\"}}".formatted(Contexts.BOT.id());
    }

    /**
     * Runs the handler over a change of the bot's own membership and returns whether it announced anything.
     */
    private boolean announces(String oldMember, String newMember) throws Exception
    {
        HandlerContext context = Contexts.template(this.directory).withUpdate(Updates.fromJson("""
                {"update_id":1,"my_chat_member":{"date":1700000000,
                 "chat":{"id":%d,"type":"supergroup","title":"Test Chat"},
                 "from":{"id":42,"is_bot":false,"first_name":"Owner"},
                 "old_chat_member":%s,"new_chat_member":%s}}""".formatted(GROUP, oldMember, newMember)));
        new BotMembershipHandler().handle(context);
        return BotMembershipHandler.claimJoin(context, GROUP);
    }

    @Test
    @DisplayName("an unrelated permission change on a configurable bot is not announced")
    void unrelatedPermissionChangeIsSilent() throws Exception
    {
        assertFalse(announces(administrator(true, true), administrator(true, false)));
    }

    @Test
    @DisplayName("losing the configuration permission is not announced")
    void losingPermissionIsSilent() throws Exception
    {
        assertFalse(announces(administrator(true, true), administrator(false, true)));
    }

    @Test
    @DisplayName("being removed from the group is not announced")
    void removalIsSilent() throws Exception
    {
        assertFalse(announces(administrator(true, true), left()));
    }
}
