package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.ConfigurationContext;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Tells a group what to do next when the bot is added to it or given the permissions it needs.
 *
 * <p>When the bot joins a group without the Change Group Information administrator permission,
 * it posts the permissions it needs and that {@code /settings} configures it afterwards. Once it
 * holds that permission, either on joining or when promoted later, the administrator who made the
 * change is shown the settings menu, the same one {@code /settings} opens.
 *
 * <p>Adding the bot through a {@code startgroup} link also sends {@code /start} with a payload;
 * {@link #claimJoin(HandlerContext, long)} lets that command stand down, so the group is not told
 * twice.
 */
@UpdateHandler(UpdateType.MY_CHAT_MEMBER)
public final class BotMembershipHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BotMembershipHandler.class);
    private static final String JOIN_CACHE_PREFIX = "bot-membership:";

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        ChatMemberUpdated change = context.update().getMyChatMember();
        String chatType = change.getChat().getType();
        if (!"group".equals(chatType) && !"supergroup".equals(chatType))
        {
            return;
        }

        boolean joined = !isPresent(change.getOldChatMember()) && isPresent(change.getNewChatMember());
        boolean configurable = canChangeInfo(change.getNewChatMember());
        boolean becameConfigurable = configurable && !canChangeInfo(change.getOldChatMember());
        if (!joined && !becameConfigurable)
        {
            return;
        }

        long chatId = change.getChat().getId();
        context.cache().put(JOIN_CACHE_PREFIX + chatId, Boolean.TRUE);
        Language lang = context.managers().languagePreferences().getChatLanguage(chatId);

        if (!configurable)
        {
            send(context, chatId, context.languages().get(lang, "bot_added", "permissions_required"));
            return;
        }

        if (!openSettings(context, chatId, change.getFrom()))
        {
            send(context, chatId, context.languages().get(lang, "bot_added", "ready"));
        }
    }

    /**
     * Reports whether the bot's arrival in the chat was just announced, consuming that record, so
     * the {@code startgroup} {@code /start} that arrives with it can stay silent.
     *
     * @param context the per-update context
     * @param chatId the group
     * @return {@code true} when this handler already told the group what to do next
     */
    public static boolean claimJoin(HandlerContext context, long chatId)
    {
        String key = JOIN_CACHE_PREFIX + chatId;
        if (context.cache().getIfPresent(key) == null)
        {
            return false;
        }
        context.cache().remove(key);
        return true;
    }

    /**
     * Opens the settings menu for whoever added or promoted the bot, when they may configure the
     * chat themselves.
     *
     * @param context the per-update context
     * @param chatId the group
     * @param user the administrator who changed the bot's membership
     * @return {@code true} when the menu was shown
     */
    private static boolean openSettings(HandlerContext context, long chatId, User user)
    {
        if (user == null || user.getIsBot())
        {
            return false;
        }

        refreshAdministrators(context, chatId);
        if (!isChangeInformationAdministrator(context, chatId, user.getId()))
        {
            return false;
        }

        try
        {
            ConfigurationContext session = context.sessions().configuration().createEphemeral(user.getId(), chatId);
            ConfigurationHandler.openMainMenu(context, session);
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Could not open the settings menu for {} in chat {}: {}", user.getId(), chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Posts an HTML message to the group.
     *
     * @param context the per-update context
     * @param chatId the group
     * @param html the message
     * @throws TelegramApiException if the message cannot be sent
     */
    private static void send(HandlerContext context, long chatId, String html) throws TelegramApiException
    {
        execute(context, "bot-added", SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(html)
                .parseMode(ParseMode.HTML)
                .build());
    }

    /**
     * Returns whether a membership state means the bot is in the chat.
     *
     * @param member the membership state
     * @return {@code true} unless the bot has left or been removed
     */
    private static boolean isPresent(ChatMember member)
    {
        String status = member.getStatus();
        return !"left".equals(status) && !"kicked".equals(status);
    }

    /**
     * Returns whether a membership state lets the bot be configured.
     *
     * @param member the membership state
     * @return {@code true} for an administrator allowed to change group information
     */
    private static boolean canChangeInfo(ChatMember member)
    {
        return member instanceof ChatMemberAdministrator administrator && Boolean.TRUE.equals(administrator.getCanChangeInfo());
    }
}
