package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.managers.UserManager;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.ChatInfo;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.UserIdentity;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records who and what the bot is seeing, before anything decides what to do about it.
 *
 * <p>Nearly every other handler depends on facts gathered here and nowhere else: who sent a
 * message, what a chat is called, which of its members are administrators, and which messages
 * belong to the same album. A permission check reads the cached administrator list; a report
 * names the reporter from the stored user; scanning acts on the album as a whole. None of that
 * works if the bookkeeping has not run.
 *
 * <p>So it runs first, as the highest-priority observer, for every message — including commands,
 * which is why {@code /start} from a brand-new user already knows who they are. It never consumes
 * an update and never answers one; it only writes down what it saw.
 */
@UpdateHandler(value = UpdateType.MESSAGE, mode = DispatchMode.OBSERVE, priority = 1000)
public final class RegistrationHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationHandler.class);

    /**
     * Records everything the incoming message tells us about its sender and its chat.
     *
     * @param context the per-update context
     */
    @Override
    public void handle(HandlerContext context)
    {
        Update update = context.update();
        Message message = update.getMessage();
        if (message == null)
        {
            return;
        }

        User author = message.getFrom();
        if (author == null)
        {
            LOGGER.debug("Update {} carries a message with no author; nothing to record", update.getUpdateId());
            return;
        }

        try
        {
            UserManager users = context.managers().users();
            users.saveUser(new UserIdentity(author.getId(), author.getUserName(), author.getFirstName(), author.getLastName()));
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to record user {} from update {}: {}", author.getId(), update.getUpdateId(), e.getMessage());
        }

        detectLanguage(context, author);
        cacheAdministrators(context, message.getChat());
        cacheChatInfo(context, message);
        pushMessageUsers(context, message);
    }

    /**
     * Detects the user's preferred language from their Telegram profile and saves it as their
     * default preference when no preference has been set yet.
     *
     * @param context the per-update command context
     * @param user the Telegram user
     */
    private static void detectLanguage(HandlerContext context, User user)
    {
        String languageCode = user.getLanguageCode();
        if (languageCode == null || languageCode.isBlank())
        {
            return;
        }

        Language detected = context.languages().resolve(languageCode);
        Language current = context.managers().languagePreferences().getUserLanguage(user.getId());
        Language botDefault = context.languages().defaultLanguage();

        if (current != botDefault)
        {
            return;
        }

        if (detected != botDefault)
        {
            try
            {
                context.managers().languagePreferences().setUserLanguage(user.getId(), detected);
            }
            catch (DatabaseException e)
            {
                LOGGER.debug("Failed to save detected language '{}' for user {}: {}",
                        detected.code(), user.getId(), e.getMessage());
            }
        }
    }

    /**
     * Ensures the administrator list for a group or supergroup is cached, refetching it when
     * absent or expired.
     *
     * @param context the per-update command context
     * @param chat the chat whose moderators should be cached
     */
    static void cacheAdministrators(HandlerContext context, Chat chat)
    {
        String chatType = chat.getType();
        if (!chatType.equals("group") && !chatType.equals("supergroup"))
        {
            return;
        }

        long chatId = chat.getId();
        try
        {
            context.chatAdmins().get(chatId, key -> loadAdministrators(context, key));
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Failed to refresh administrators for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Caches a lightweight snapshot of the message's chat (name, id and type) for later display
     * in configuration menus. Only group and supergroup chats are cached because those are the
     * chats that can be configured through the private settings menu.
     *
     * @param context the per-update command context
     * @param message the message being processed
     */
    private static void cacheChatInfo(HandlerContext context, Message message)
    {
        String chatType = message.getChat().getType();
        if (!chatType.equals("group") && !chatType.equals("supergroup"))
        {
            return;
        }

        String name = message.getChat().getTitle();
        if (name == null || name.isBlank())
        {
            name = message.getChat().getUserName();
        }
        if (name == null || name.isBlank())
        {
            name = "Chat " + message.getChatId();
        }

        context.chatInfo().put(message.getChatId(), new ChatInfo(message.getChatId(), name, chatType));
    }

    /**
     * Pushes every user reachable from the message as a Federation entity when the message is in
     * a group chat where the bot is enabled.
     *
     * <p>The entity address uses the Telegram convention {@code <id>@telegram.org}. Pushing is
     * best-effort: failures are logged and never prevent the update from being routed.
     *
     * @param context the per-update command context
     * @param message the incoming message
     */
    private static void pushMessageUsers(HandlerContext context, Message message)
    {
        String chatType = message.getChat().getType();
        if (!chatType.equals("group") && !chatType.equals("supergroup"))
        {
            return;
        }

        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(message.getChatId());
        if (!configuration.enabled())
        {
            return;
        }

        if (configuration.privacyMode())
        {
            return;
        }

        if (!context.federation().isAvailable())
        {
            return;
        }

        for (User user : collectUsers(message))
        {
            pushUser(context.federation(), user);
        }
    }

    /**
     * Pushes a single Telegram user to the Federation server as {@code <id>@telegram.org}, with
     * every property of the user as metadata ({@code first_name}, {@code is_bot},
     * {@code language_code}, ...). Properties the update did not carry are left out rather than
     * sent as {@code null}, because the server merges metadata and a {@code null} would erase what
     * an earlier, fuller update recorded.
     *
     * @param federation the Federation server
     * @param user the Telegram user
     */
    static void pushUser(FederationService federation, User user)
    {
        if (!federation.isAuthenticated())
        {
            // Publishing an entity is refused to the bot unconditionally without client
            // permissions, so there is nothing worth attempting or logging here.
            return;
        }

        try
        {
            federation.publishEntity("telegram.org", String.valueOf(user.getId()), FlatMetadata.of(user));
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to push Federation entity for user {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Collects all distinct users reachable from the given message.
     *
     * @param message the incoming message
     * @return the list of users
     */
    private static List<User> collectUsers(Message message)
    {
        Map<Long, User> users = new LinkedHashMap<>();
        addUser(users, message.getFrom());
        addUser(users, message.getForwardFrom());
        addUser(users, message.getViaBot());
        addUser(users, message.getLeftChatMember());
        addUser(users, message.getSenderBusinessBot());
        addUser(users, message.getGuestBotCallerUser());
        for (User user : message.getNewChatMembers())
        {
            addUser(users, user);
        }
        Message replyTo = message.getReplyToMessage();
        if (replyTo != null && !MessageHelper.isReplyToTopicHeader(message))
        {
            addUser(users, replyTo.getFrom());
            addUser(users, replyTo.getForwardFrom());
            addUser(users, replyTo.getViaBot());
        }
        return new ArrayList<>(users.values());
    }

    /**
     * Fetches the moderator list for the given chat from the Telegram API: every chat owner plus
     * every administrator who can delete messages or restrict members. The cache drives moderation
     * eligibility and private moderator-notification delivery without per-notification API calls.
     *
     * @param context the per-update command context
     * @param chatId the Telegram chat id
     * @return the cached administrator info, or {@code null} when the fetch failed (then
     * nothing is cached and the next update retries)
     */
    private static List<AdminInfo> loadAdministrators(HandlerContext context, Long chatId)
    {
        try
        {
            List<ChatMember> members = context.telegramClient().execute(GetChatAdministrators.builder().chatId(chatId).build());
            List<AdminInfo> administrators = new ArrayList<>();
            for (ChatMember member : members)
            {
                long userId = member.getUser().getId();
                if (member instanceof ChatMemberOwner)
                {
                    administrators.add(new AdminInfo(userId, true, true, true));
                }
                else if (member instanceof ChatMemberAdministrator administrator
                        && (Boolean.TRUE.equals(administrator.getCanDeleteMessages())
                        || Boolean.TRUE.equals(administrator.getCanRestrictMembers())))
                {
                    administrators.add(new AdminInfo(userId, false,
                            Boolean.TRUE.equals(administrator.getCanDeleteMessages()),
                            Boolean.TRUE.equals(administrator.getCanRestrictMembers())));
                }
            }
            return administrators;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Failed to load administrators for chat {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    /**
     * Adds a user to the map keyed by id when non-{@code null}.
     *
     * @param users the accumulated user map
     * @param user the user to add
     */
    private static void addUser(Map<Long, User> users, User user)
    {
        if (user == null)
        {
            return;
        }
        users.putIfAbsent(user.getId(), user);
    }
}
