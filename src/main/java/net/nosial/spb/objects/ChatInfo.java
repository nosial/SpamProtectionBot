package net.nosial.spb.objects;

/**
 * Telegram chat conext.
 *
 * @param chatId the unique Telegram chat identifier
 * @param name the display name of the chat (title for groups/channels, first name for private chats)
 * @param type the Telegram chat type (e.g. "private", "group", "supergroup", "channel")
 */
public record ChatInfo(long chatId, String name, String type)
{
}
