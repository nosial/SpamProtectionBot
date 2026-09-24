package net.nosial.spb.utilities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import net.nosial.jfederation.records.ContentInput;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** Selects the textual content carried by a Telegram message or media caption. */
public final class MessageContent
{
    /**
     * Returns ordinary message text when present, otherwise the media caption.
     *
     * @param message Telegram message
     * @return text or caption, or {@code null} when the message has neither
     */
    public static String textOrCaption(Message message)
    {
        return message.getText() != null ? message.getText() : message.getCaption();
    }

    /**
     * Builds a {@code ContentInput} object from the given {@code Message} instance.
     * This method condenses information from a Telegram message into a structured format
     * suitable for content scanning. If {@code privacyMode} is enabled, only the
     * scanned content is included.
     *
     * @param message the {@code Message} instance containing the information to be processed
     * @param privacyMode a boolean flag indicating whether to minimize the amount of data included
     *                    for privacy reasons
     * @return a {@code ContentInput} instance representing the processed content from the message
     */
    public static ContentInput buildScanInput(Message message, boolean privacyMode)
    {
        if (privacyMode)
        {
            return new ContentInput(contentToScan(message));
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chat_id", message.getChatId());
        fields.put("chat_type", message.getChat().getType());
        fields.put("message_id", message.getMessageId());
        fields.put("author_id", message.getFrom().getId());
        fields.put("content_type", message.getText() != null ? "text" : "caption");
        fields.put("has_media", hasMedia(message));
        fields.put("has_link", hasLink(message));
        fields.put("message_thread_id", message.getMessageThreadId());
        fields.put("sent_at", message.getDate());
        fields.put("author_username", message.getFrom().getUserName());

        // The summary fields first, then the message itself with every property it carries.
        return new ContentInput(contentToScan(message),
                "Telegram message " + message.getMessageId() + " in chat " + message.getChatId(),
                "telegram_message", false, FlatMetadata.of(fields, message));
    }

    /**
     * Retrieves the text or caption content from the provided Telegram message.
     *
     * @param message the Telegram message from which to extract the content
     * @return the text of the message if present, otherwise the caption;
     *         returns {@code null} if neither is available
     */
    public static String contentToScan(Message message)
        {
            return MessageContent.textOrCaption(message);
        }

    /**
     * Determines whether the given message contains any type of media.
     * This method considers various forms of media such as audio, document, photo, sticker,
     * video, contact, location, venue, animation, voice, game, invoice, video note, poll, or dice.
     *
     * @param message the Telegram message to be checked for media content
     * @return {@code true} if the message contains any type of media; {@code false} otherwise
     */
    public static boolean hasMedia(Message message)
    {
        return message.getAudio() != null
                || message.getDocument() != null
                || (message.getPhoto() != null && !message.getPhoto().isEmpty())
                || message.getSticker() != null
                || message.getVideo() != null
                || message.getContact() != null
                || message.getLocation() != null
                || message.getVenue() != null
                || message.getAnimation() != null
                || message.getVoice() != null
                || message.getGame() != null
                || message.getInvoice() != null
                || message.getVideoNote() != null
                || message.getPoll() != null
                || message.getDice() != null;
    }

    /**
     * Determines whether the provided Telegram message contains any hyperlinks.
     * This is achieved by examining the message's entities (such as URLs or text links)
     * in both the main content and the caption.
     *
     * @param message the Telegram message to be checked for hyperlinks
     * @return {@code true} if the message contains at least one hyperlink, {@code false} otherwise
     */
    public static boolean hasLink(Message message)
    {
        return hasLink(message.getEntities()) || hasLink(message.getCaptionEntities());
    }

    /**
     * Checks whether the given list of message entities contains any links.
     * Identifies links by checking entities of type "url" or "text_link".
     *
     * @param entities the list of {@code MessageEntity} objects to be checked for links
     * @return {@code true} if at least one entity in the list represents a link; {@code false} otherwise
     */
    private static boolean hasLink(List<MessageEntity> entities)
    {
        if (entities == null)
        {
            return false;
        }
        for (MessageEntity entity : entities)
        {
            if (entity != null && ("url".equals(entity.getType()) || "text_link".equals(entity.getType())))
            {
                return true;
            }
        }
        return false;
    }
}
