package net.nosial.spb.handlers.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests who may change a chat's settings through the configuration menu: the owner and every
 * administrator with Change Group Information. Enabling and disabling the bot stays with the owner.
 */
class ConfigurationPermissionsTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses a chat member the way Telegram reports one.
     *
     * @param status the member's status
     * @param canChangeInfo the administrator's Change Group Information permission
     * @return the chat member
     */
    private static ChatMember member(String status, boolean canChangeInfo) throws Exception
    {
        return MAPPER.readValue("""
                {"status":"%s","user":{"id":7,"is_bot":false,"first_name":"Member"},
                 "can_be_edited":false,"is_anonymous":false,"can_manage_chat":true,
                 "can_delete_messages":true,"can_manage_video_chats":false,"can_restrict_members":true,
                 "can_promote_members":false,"can_change_info":%s,"can_invite_users":true,
                 "can_post_stories":false,"can_edit_stories":false,"can_delete_stories":false}"""
                .formatted(status, canChangeInfo), ChatMember.class);
    }

    @Test
    @DisplayName("an administrator with Change Group Information may change settings but not enable or disable")
    void changeInfoAdministratorConfiguresButDoesNotToggle() throws Exception
    {
        ChatMember administrator = member("administrator", true);

        assertTrue(ConfigurationHandler.canChangeInfo(administrator));
        assertFalse(ConfigurationHandler.isChatOwner(administrator));
    }

    @Test
    @DisplayName("an administrator without Change Group Information may not change settings")
    void moderatorWithoutChangeInfoIsRefused() throws Exception
    {
        assertFalse(ConfigurationHandler.canChangeInfo(member("administrator", false)));
    }

    @Test
    @DisplayName("the owner may do both")
    void ownerMayDoBoth() throws Exception
    {
        ChatMember owner = member("creator", true);

        assertTrue(ConfigurationHandler.canChangeInfo(owner));
        assertTrue(ConfigurationHandler.isChatOwner(owner));
    }

    @Test
    @DisplayName("a regular member, or a failed lookup, may do neither")
    void othersAreRefused() throws Exception
    {
        assertFalse(ConfigurationHandler.canChangeInfo(member("member", false)));
        assertFalse(ConfigurationHandler.canChangeInfo(null));
    }
}
