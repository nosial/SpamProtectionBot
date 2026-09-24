package net.nosial.spb.utilities;

import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.DeclineChatJoinRequest;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;

/**
 * Stateless utility for executing Telegram moderation actions (delete, restrict, ban).
 *
 * <p>Every method is self-contained and returns a boolean indicating success or failure, with
 * errors logged at the appropriate level. This class centralises the Telegram API calls that
 * were previously duplicated across {@code UpdateHandler}, {@code ReportHandler}, and
 * other moderation entry points.
 */
public final class ModerationActions
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ModerationActions.class);

    /**
     * Deletes a message from a chat.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param messageId the message id to delete
     * @return {@code true} if the message was deleted successfully
     */
    public static boolean deleteMessage(HandlerContext context, long chatId, int messageId)
    {
        try
        {
            context.telegramClient().execute(DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.debug("Unable to delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a message, using the message's own chat and message id.
     *
     * @param context the per-update command context
     * @param message the message to delete
     * @return {@code true} if the message was deleted successfully
     */
    public static boolean deleteMessage(HandlerContext context, Message message)
    {
        return deleteMessage(context, message.getChatId(), message.getMessageId());
    }

    /**
     * Restricts a user in a chat, revoking all send permissions.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to restrict
     * @param untilEpochSeconds the Unix time at which the restriction lifts (Telegram's
     *                          {@code until_date}, not a duration; see {@link #untilFromNow(long)}),
     *                          or {@code null} for permanent
     * @return {@code true} if the restriction was applied successfully
     */
    public static boolean restrictUser(HandlerContext context, long chatId, long userId, Long untilEpochSeconds)
    {
        try
        {
            var builder = RestrictChatMember.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .permissions(allDeniedPermissions())
                    .useIndependentChatPermissions(true);
            if (untilEpochSeconds != null)
            {
                builder.untilDate(Math.toIntExact(untilEpochSeconds));
            }
            context.telegramClient().execute(builder.build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to restrict user {} in chat {}: {}", userId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Bans a user from a chat.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to ban
     * @param untilEpochSeconds the Unix time at which the ban lifts (Telegram's {@code until_date},
     *                          not a duration; see {@link #untilFromNow(long)}), or {@code null}
     *                          for permanent
     * @return {@code true} if the ban was applied successfully
     */
    public static boolean banUser(HandlerContext context, long chatId, long userId, Long untilEpochSeconds)
    {
        try
        {
            var builder = BanChatMember.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId);
            if (untilEpochSeconds != null)
            {
                builder.untilDate(Math.toIntExact(untilEpochSeconds));
            }
            context.telegramClient().execute(builder.build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to ban user {} in chat {}: {}", userId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Restricts a user so that only plain-text messages are allowed (no media).
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id to restrict
     * @return {@code true} if the restriction was applied successfully
     */
    public static boolean restrictMediaOnly(HandlerContext context, long chatId, long userId)
    {
        try
        {
            context.telegramClient().execute(RestrictChatMember.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .permissions(mediaOnlyPermissions())
                    .useIndependentChatPermissions(true)
                    .build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to apply media-only restriction to user {} in chat {}: {}", userId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Approves a pending chat join request.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id whose request to approve
     * @return {@code true} if the request was approved successfully
     */
    public static boolean approveJoinRequest(HandlerContext context, long chatId, long userId)
    {
        try
        {
            context.telegramClient().execute(ApproveChatJoinRequest.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to approve join request from user {} in chat {}: {}", userId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Declines a pending chat join request.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @param userId the Telegram user id whose request to decline
     * @return {@code true} if the request was declined successfully
     */
    public static boolean declineJoinRequest(HandlerContext context, long chatId, long userId)
    {
        try
        {
            context.telegramClient().execute(DeclineChatJoinRequest.builder()
                    .chatId(String.valueOf(chatId))
                    .userId(userId)
                    .build());
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to decline join request from user {} in chat {}: {}", userId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the Unix time a given number of seconds from now, for the {@code until} argument of
     * {@link #restrictUser} and {@link #banUser}.
     *
     * <p>Telegram's {@code until_date} is an absolute time, and it treats one less than 30 seconds
     * from now (or more than 366 days away) as "forever": passing a bare duration such as 3600
     * means 1970 and silently makes a one-hour mute permanent.
     *
     * @param seconds how long from now, at least 30 for Telegram to honour it
     * @return the epoch-second timestamp
     */
    public static long untilFromNow(long seconds)
    {
        return Instant.now().getEpochSecond() + seconds;
    }

    /**
     * Computes the epoch-second timestamp at which a temporary restriction should lift.
     *
     * <p>Uses the Federation-suggested lift timestamp when it is sufficiently far in the future;
     * otherwise defaults to 24 hours from now.</p>
     *
     * @param suggestedLiftTimestamp the Federation-suggested lift timestamp, or {@code null}
     * @return the epoch-second expiry timestamp
     */
    public static int temporaryRestrictionUntil(Long suggestedLiftTimestamp)
    {
        long now = Instant.now().getEpochSecond();
        if (suggestedLiftTimestamp != null && suggestedLiftTimestamp > now + 30
                && suggestedLiftTimestamp <= Integer.MAX_VALUE)
        {
            return suggestedLiftTimestamp.intValue();
        }
        return Math.toIntExact(now + 86_400);
    }

    /**
     * Returns a {@link ChatPermissions} instance with all send permissions denied.
     */
    public static ChatPermissions allDeniedPermissions()
    {
        return ChatPermissions.builder()
                .canSendMessages(false)
                .canSendAudios(false)
                .canSendDocuments(false)
                .canSendPhotos(false)
                .canSendVideos(false)
                .canSendVideoNotes(false)
                .canSendVoiceNotes(false)
                .canSendPolls(false)
                .canSendOtherMessages(false)
                .canAddWebPagePreviews(false)
                .canChangeInfo(false)
                .canInviteUsers(false)
                .canPinMessages(false)
                .build();
    }

    /**
     * Returns a {@link ChatPermissions} instance that allows only plain-text messages.
     */
    public static ChatPermissions mediaOnlyPermissions()
    {
        return ChatPermissions.builder()
                .canSendMessages(true)
                .canSendAudios(false)
                .canSendDocuments(false)
                .canSendPhotos(false)
                .canSendVideos(false)
                .canSendVideoNotes(false)
                .canSendVoiceNotes(false)
                .canSendPolls(false)
                .canSendOtherMessages(false)
                .canAddWebPagePreviews(false)
                .canChangeInfo(false)
                .canInviteUsers(false)
                .canPinMessages(false)
                .build();
    }
}
