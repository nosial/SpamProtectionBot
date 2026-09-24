package net.nosial.spb.enums;

import net.nosial.spb.classes.UpdateHandler;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The kinds of update a handler can declare interest in.
 *
 * <p>Each constant knows how to recognise itself in an {@link Update}, so routing never needs a
 * chain of {@code if (update.hasX())} checks: a handler declares the types it serves through the
 * {@link UpdateHandler} annotation and the dispatcher matches them.
 *
 * <p>{@link #ANY} matches every update and is the default for handlers that observe the whole
 * stream. {@link #COMMAND} is a refinement of {@link #MESSAGE}: it matches only text messages
 * whose first token is a {@code /command} entity, so command handlers never have to parse the
 * message themselves.
 */
public enum UpdateType
{
    /** Matches every update, whatever it carries. */
    ANY(update -> true, update -> null),

    /** A regular incoming message. */
    MESSAGE(Update::hasMessage, Update::getMessage),

    /** A message whose first token is a {@code /command}. */
    COMMAND(UpdateType::isCommandMessage, Update::getMessage),

    /** A previously sent message that was edited. */
    EDITED_MESSAGE(Update::hasEditedMessage, Update::getEditedMessage),

    /** A new post in a channel the bot follows. */
    CHANNEL_POST(Update::hasChannelPost, Update::getChannelPost),

    /** A channel post that was edited. */
    EDITED_CHANNEL_POST(Update::hasEditedChannelPost, Update::getEditedChannelPost),

    /** An inline button press carrying callback data. */
    CALLBACK_QUERY(Update::hasCallbackQuery, update -> null),

    /** An inline query typed in any chat. */
    INLINE_QUERY(Update::hasInlineQuery, update -> null),

    /** The result the user chose from an inline query. */
    CHOSEN_INLINE_QUERY(Update::hasChosenInlineQuery, update -> null),

    /** A shipping query from an invoice with flexible price. */
    SHIPPING_QUERY(Update::hasShippingQuery, update -> null),

    /** A pre-checkout query from a payment about to be confirmed. */
    PRE_CHECKOUT_QUERY(Update::hasPreCheckoutQuery, update -> null),

    /** A poll the bot owns whose state changed. */
    POLL(Update::hasPoll, update -> null),

    /** A user's answer to a non-anonymous poll. */
    POLL_ANSWER(Update::hasPollAnswer, update -> null),

    /** The bot's own membership in a chat changed. */
    MY_CHAT_MEMBER(Update::hasMyChatMember, update -> null),

    /** Another member's status in a chat changed. */
    CHAT_MEMBER(Update::hasChatMember, update -> null),

    /** A request to join a chat the bot administers. */
    CHAT_JOIN_REQUEST(Update::hasChatJoinRequest, update -> null),

    /** A Telegram Business account connected the bot or changed its connection. */
    BUSINESS_CONNECTION(Update::hasBusinessConnection, update -> null),

    /** A message received through a connected Telegram Business account. */
    BUSINESS_MESSAGE(Update::hasBusinessMessage, Update::getBusinessMessage),

    /** A business message that was edited. */
    EDITED_BUSINESS_MESSAGE(Update::hasEditedBusinessMessage, Update::getEditedBuinessMessage),

    /** One or more business messages were deleted. */
    DELETED_BUSINESS_MESSAGES(Update::hasDeletedBusinessMessage, update -> null),

    /** A reaction on a message the bot can see changed. */
    MESSAGE_REACTION(Update::hasMessageReaction, update -> null),

    /** The anonymous reaction counters of a message changed. */
    MESSAGE_REACTION_COUNT(Update::hasMessageReactionCount, update -> null),

    /** A chat the bot administers was boosted. */
    CHAT_BOOST(Update::hasChatBoost, update -> null),

    /** A boost was removed from a chat the bot administers. */
    REMOVED_CHAT_BOOST(Update::hasRemovedChatBoost, update -> null);

    private final Predicate<Update> matcher;
    private final Function<Update, Message> messageExtractor;

    /**
     * Creates an update type.
     *
     * @param matcher recognises the type in an incoming update
     * @param messageExtractor returns the message this type carries, or {@code null} when it
     *                         carries none
     */
    UpdateType(Predicate<Update> matcher, Function<Update, Message> messageExtractor)
    {
        this.matcher = matcher;
        this.messageExtractor = messageExtractor;
    }

    /**
     * Returns whether the given update is of this type.
     *
     * @param update the incoming update
     * @return {@code true} when the update matches
     */
    public boolean matches(Update update)
    {
        return update != null && this.matcher.test(update);
    }

    /**
     * Returns the message this type carries in the given update.
     *
     * @param update the incoming update
     * @return the message, or {@code null} when this type carries no message or the update is not
     *         of this type
     */
    public Message messageOf(Update update)
    {
        if (update == null || !matches(update))
        {
            return null;
        }

        return this.messageExtractor.apply(update);
    }

    /**
     * Returns the most specific type of the given update, used for diagnostic logging.
     *
     * <p>{@link #COMMAND} wins over {@link #MESSAGE} for command messages; {@link #ANY} is never
     * returned as it is not a concrete kind of update.
     *
     * @param update the incoming update
     * @return the resolved type, or {@code null} when the update carries nothing recognised
     */
    public static UpdateType of(Update update)
    {
        if (update == null)
        {
            return null;
        }

        for (UpdateType type : values())
        {
            if (type != ANY && type != MESSAGE && type.matches(update))
            {
                return type;
            }
        }

        return MESSAGE.matches(update) ? MESSAGE : null;
    }

    /**
     * Returns whether the update carries a text message whose first token is a {@code /command}.
     *
     * @param update the incoming update
     * @return {@code true} when the update is a command message
     */
    private static boolean isCommandMessage(Update update)
    {
        if (!update.hasMessage())
        {
            return false;
        }

        String text = update.getMessage().getText();
        if (text == null)
        {
            return false;
        }

        String trimmed = text.stripLeading();
        return trimmed.length() > 1 && trimmed.charAt(0) == '/' && !Character.isWhitespace(trimmed.charAt(1));
    }
}
