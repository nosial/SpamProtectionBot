package net.nosial.spb.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.UncheckedIOException;
import java.io.IOException;

/**
 * Builds {@link Update} instances for tests.
 *
 * <p>Updates are deserialised from the JSON Telegram actually sends rather than assembled field by
 * field, so the tests exercise the same objects the bot sees in production. The API's {@code date}
 * field doubles as the discriminator between an accessible and an inaccessible message, so every
 * message here carries a real timestamp.
 */
public final class Updates
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A plausible message timestamp; zero would deserialise as an inaccessible message. */
    private static final long DATE = 1_700_000_000L;

    private Updates()
    {
    }

    /**
     * Builds an update from a raw Telegram JSON payload.
     *
     * @param json the update payload
     * @return the parsed update
     */
    public static Update fromJson(String json)
    {
        try
        {
            return MAPPER.readValue(json, Update.class);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Invalid update JSON: " + json, e);
        }
    }

    /**
     * Builds a private-chat text message from user 42.
     *
     * @param updateId the update id
     * @param text the message text
     * @return the update
     */
    public static Update privateMessage(int updateId, String text)
    {
        return message(updateId, 42L, "private", text);
    }

    /**
     * Builds a supergroup text message from user 42.
     *
     * @param updateId the update id
     * @param text the message text
     * @return the update
     */
    public static Update groupMessage(int updateId, String text)
    {
        return message(updateId, -1001234567890L, "supergroup", text);
    }

    /**
     * Builds a text message in the given chat.
     *
     * @param updateId the update id
     * @param chatId the chat the message was sent in
     * @param chatType the Telegram chat type
     * @param text the message text
     * @return the update
     */
    public static Update message(int updateId, long chatId, String chatType, String text)
    {
        return fromJson("""
                {"update_id":%d,"message":{"message_id":%d,"date":%d,
                 "chat":{"id":%d,"type":"%s","title":"Test Chat"},
                 "from":{"id":42,"is_bot":false,"first_name":"Tester","username":"tester"},
                 "text":%s}}"""
                .formatted(updateId, updateId, DATE, chatId, chatType, quote(text)));
    }

    /**
     * Builds an edited private message.
     *
     * @param updateId the update id
     * @param text the new message text
     * @return the update
     */
    public static Update editedMessage(int updateId, String text)
    {
        return fromJson("""
                {"update_id":%d,"edited_message":{"message_id":%d,"date":%d,"edit_date":%d,
                 "chat":{"id":42,"type":"private"},
                 "from":{"id":42,"is_bot":false,"first_name":"Tester"},
                 "text":%s}}"""
                .formatted(updateId, updateId, DATE, DATE + 60, quote(text)));
    }

    /**
     * Builds a channel post.
     *
     * @param updateId the update id
     * @param text the post text
     * @return the update
     */
    public static Update channelPost(int updateId, String text)
    {
        return fromJson("""
                {"update_id":%d,"channel_post":{"message_id":%d,"date":%d,
                 "chat":{"id":-1009876543210,"type":"channel","title":"Test Channel"},
                 "text":%s}}"""
                .formatted(updateId, updateId, DATE, quote(text)));
    }

    /**
     * Builds an inline button press.
     *
     * @param updateId the update id
     * @param data the callback data of the pressed button
     * @return the update
     */
    public static Update callbackQuery(int updateId, String data)
    {
        return fromJson("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"instance",
                 "from":{"id":42,"is_bot":false,"first_name":"Tester"},
                 "message":{"message_id":%d,"date":%d,"chat":{"id":42,"type":"private"},"text":"menu"},
                 "data":%s}}"""
                .formatted(updateId, updateId, updateId, DATE, quote(data)));
    }

    /**
     * Builds an update carrying nothing any handler recognises.
     *
     * @param updateId the update id
     * @return the update
     */
    public static Update poll(int updateId)
    {
        return fromJson("""
                {"update_id":%d,"poll":{"id":"poll-%d","question":"?","options":[],
                 "total_voter_count":0,"is_closed":false,"is_anonymous":true,"type":"regular",
                 "allows_multiple_answers":false}}"""
                .formatted(updateId, updateId));
    }

    /**
     * Quotes a value as a JSON string, or as {@code null} when absent.
     *
     * @param value the value to quote
     * @return the JSON literal
     */
    private static String quote(String value)
    {
        if (value == null)
        {
            return "null";
        }

        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /**
     * Builds a Telegram Business connection being enabled or disabled.
     *
     * @param updateId the update id
     * @param enabled whether the owner turned the connection on
     * @return the update
     */
    public static Update businessConnection(int updateId, boolean enabled)
    {
        return fromJson("""
                {"update_id":%d,"business_connection":{"id":"conn-1","date":%d,"is_enabled":%b,
                 "user_chat_id":42,
                 "user":{"id":42,"is_bot":false,"first_name":"Owner","username":"owner"}}}"""
                .formatted(updateId, DATE, enabled));
    }

    /**
     * Builds a message received through a Telegram Business connection.
     *
     * @param updateId the update id
     * @param text the message text
     * @return the update
     */
    public static Update businessMessage(int updateId, String text)
    {
        return fromJson("""
                {"update_id":%d,"business_message":{"message_id":%d,"date":%d,
                 "business_connection_id":"conn-1",
                 "chat":{"id":99,"type":"private"},
                 "from":{"id":99,"is_bot":false,"first_name":"Stranger"},
                 "text":%s}}"""
                .formatted(updateId, updateId, DATE, quote(text)));
    }

    /**
     * Builds a notification that business messages were deleted.
     *
     * @param updateId the update id
     * @return the update
     */
    public static Update deletedBusinessMessages(int updateId)
    {
        return fromJson("""
                {"update_id":%d,"deleted_business_messages":{"business_connection_id":"conn-1",
                 "chat":{"id":99,"type":"private"},"message_ids":[1,2]}}"""
                .formatted(updateId));
    }

    /**
     * Builds a new member joining a chat.
     *
     * @param updateId the update id
     * @return the update
     */
    public static Update chatMemberJoined(int updateId)
    {
        return fromJson("""
                {"update_id":%d,"chat_member":{"date":%d,
                 "chat":{"id":-1001234567890,"type":"supergroup","title":"Test Chat"},
                 "from":{"id":42,"is_bot":false,"first_name":"Tester"},
                 "old_chat_member":{"status":"left","user":{"id":99,"is_bot":false,"first_name":"New"}},
                 "new_chat_member":{"status":"member","user":{"id":99,"is_bot":false,"first_name":"New"}}}}"""
                .formatted(updateId, DATE));
    }
}
