package net.nosial.spb.handlers.group;

import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests when scanning tells a Passive chat's moderators about a member, so a #SCAN_MATCH is only
 * ever sent for something that was actually flagged.
 */
class ScanningNotificationTest
{
    @TempDir
    Path directory;

    /**
     * Builds a group message from member 99 with the given extra fields.
     *
     * @param fields extra message fields, each followed by a comma
     * @return the message
     */
    private static Message message(String fields)
    {
        return Updates.fromJson("""
                {"update_id":1,"message":{"message_id":7,"date":1700000000,%s
                 "chat":{"id":-1003540281179,"type":"supergroup","title":"The Midnight Channel"},
                 "from":{"id":99,"is_bot":false,"first_name":"Member"}}}""".formatted(fields)).getMessage();
    }

    @Test
    @DisplayName("a member's first message that nothing flagged sends no notification")
    void cleanFirstMessageIsSilent()
    {
        HandlerContext context = Contexts.template(this.directory);

        assertFalse(ScanningHandler.passiveObservationDue(context, message("\"text\":\"hi\","), null, null));
    }

    @Test
    @DisplayName("a flag is reported once, a repeat of it is not, and coming back clean is silent")
    void onlyNewFlagsAreReported()
    {
        HandlerContext context = Contexts.template(this.directory);
        Message message = message("\"text\":\"hi\",");

        assertTrue(ScanningHandler.passiveObservationDue(context, message, SuggestedAction.CAUTION, null));
        assertFalse(ScanningHandler.passiveObservationDue(context, message, SuggestedAction.CAUTION, null));
        assertFalse(ScanningHandler.passiveObservationDue(context, message, null, null));
        assertTrue(ScanningHandler.passiveObservationDue(context, message, SuggestedAction.CAUTION, null),
                "flagged again after a clean message is new again");
    }

    @Test
    @DisplayName("members joining or leaving are left to join protection")
    void membershipServiceMessagesAreRecognised()
    {
        assertTrue(ScanningHandler.isMembershipServiceMessage(message(
                "\"new_chat_members\":[{\"id\":99,\"is_bot\":false,\"first_name\":\"Member\"}],")));
        assertTrue(ScanningHandler.isMembershipServiceMessage(message(
                "\"left_chat_member\":{\"id\":99,\"is_bot\":false,\"first_name\":\"Member\"},")));
        assertFalse(ScanningHandler.isMembershipServiceMessage(message("\"text\":\"hello\",")));
    }
}
