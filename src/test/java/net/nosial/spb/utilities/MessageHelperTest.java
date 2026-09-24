package net.nosial.spb.utilities;

import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for telling a real reply apart from a message's implicit attachment to its forum topic.
 */
class MessageHelperTest
{
    /**
     * Builds a {@code /report} message replying to message 50.
     *
     * @param threadFields the message's thread fields, or an empty string for none
     * @return the message
     */
    private static Message replyTo50(String threadFields)
    {
        return Updates.fromJson("""
                {"update_id":1,"message":{"message_id":51,"date":1700000000,
                 "chat":{"id":-1001234567890,"type":"supergroup","title":"Group"},
                 "from":{"id":42,"is_bot":false,"first_name":"Reporter"},
                 "text":"/report",%s
                 "reply_to_message":{"message_id":50,"date":1700000000,
                  "chat":{"id":-1001234567890,"type":"supergroup","title":"Group"},
                  "from":{"id":99,"is_bot":false,"first_name":"Spammer"},"text":"buy now"}}}"""
                .formatted(threadFields)).getMessage();
    }

    @Test
    @DisplayName("a plain reply in an ordinary supergroup is a real reply")
    void ordinaryGroupReplyIsARealReply()
    {
        // Telegram sets message_thread_id on replies outside forums too, to the first message of
        // the reply chain, so a direct reply carries its target's id as the thread id. Treating it
        // as a topic header made /report answer every reply with its usage text.
        assertFalse(MessageHelper.isReplyToTopicHeader(replyTo50("\"message_thread_id\":50,")));
    }

    @Test
    @DisplayName("a message attached to its forum topic's header is not a real reply")
    void forumTopicHeaderIsNotARealReply()
    {
        assertTrue(MessageHelper.isReplyToTopicHeader(
                replyTo50("\"message_thread_id\":50,\"is_topic_message\":true,")));
    }

    @Test
    @DisplayName("a reply to another message inside a forum topic is a real reply")
    void replyInsideForumTopicIsARealReply()
    {
        assertFalse(MessageHelper.isReplyToTopicHeader(
                replyTo50("\"message_thread_id\":12,\"is_topic_message\":true,")));
    }

    @Test
    @DisplayName("a reply with no thread at all is a real reply")
    void replyWithoutThreadIsARealReply()
    {
        assertFalse(MessageHelper.isReplyToTopicHeader(replyTo50("")));
    }
}
