package net.nosial.spb.objects.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.classes.Cache;
import net.nosial.spb.classes.BotServices;
import net.nosial.spb.classes.Configuration;
import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.classes.managers.ManagerRegistry;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.BotIdentity;
import net.nosial.spb.objects.ChatInfo;
import net.nosial.spb.objects.SessionRegistry;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * Everything a handler may need while processing one update.
 *
 * <p>Handlers declare no dependencies of their own: the dispatcher derives a context per update
 * from a shared template through {@link #withUpdate(Update)} and passes it to every handler that
 * runs, which keeps handlers free of wiring and makes them trivially testable.
 *
 * <p>Contexts are immutable and the services they carry are thread-safe, so the same template is
 * safely shared by every worker thread. {@link #database()} in particular hands out one pooled
 * connection per call, so concurrent handlers never contend for a shared connection.
 *
 * @param update the incoming update being processed
 * @param services the bot's long-lived services, reached through the accessors below
 */
public record HandlerContext(Update update, BotServices services)
{
    /**
     * Rejects a context without the services a handler would immediately reach for.
     */
    public HandlerContext
    {
        Objects.requireNonNull(services, "services must not be null");
    }

    /**
     * Creates the template context, bound to no update, that per-update contexts are derived from.
     *
     * @param services the bot's long-lived services
     */
    public HandlerContext(BotServices services)
    {
        this(null, services);
    }

    /**
     * Returns the Telegram API client used to answer.
     *
     * @return the client
     */
    public OkHttpTelegramClient telegramClient()
    {
        return this.services.telegramClient();
    }

    /**
     * Returns the SQLite access layer.
     *
     * @return the database
     */
    public Database database()
    {
        return this.services.database();
    }

    /**
     * Returns the record-keeping layer over the database.
     *
     * @return the managers
     */
    public ManagerRegistry managers()
    {
        return this.services.managers();
    }

    /**
     * Returns the validated process configuration.
     *
     * @return the configuration
     */
    public Configuration configuration()
    {
        return this.services.configuration();
    }

    /**
     * Returns the Federation server, which reports itself unavailable when unconfigured.
     *
     * @return the Federation service
     */
    public FederationService federation()
    {
        return this.services.federation();
    }

    /**
     * Returns the translated strings for every user-facing message.
     *
     * @return the language manager
     */
    public LanguageManager languages()
    {
        return this.services.languages();
    }

    /**
     * Returns the short-lived dialogs currently open.
     *
     * @return the session registry
     */
    public SessionRegistry sessions()
    {
        return this.services.sessions();
    }

    /**
     * Returns the shared cache for transient runtime state.
     *
     * @return the runtime cache
     */
    public Cache<String, Object> cache()
    {
        return this.services.cache();
    }

    /**
     * Returns the per-chat administrator snapshots maintained by the registration handler.
     *
     * @return the administrator cache
     */
    public Cache<Long, List<AdminInfo>> chatAdmins()
    {
        return this.services.chatAdmins();
    }

    /**
     * Returns the per-chat name and type snapshots maintained by the registration handler.
     *
     * @return the chat information cache
     */
    public Cache<Long, ChatInfo> chatInfo()
    {
        return this.services.chatInfo();
    }

    /**
     * Returns the bot's own identity as reported by Telegram.
     *
     * @return the identity
     */
    public BotIdentity bot()
    {
        return this.services.identity();
    }

    /**
     * Returns the Jackson mapper the Telegram client serialises with.
     *
     * @return the object mapper
     */
    public ObjectMapper objectMapper()
    {
        return this.services.objectMapper();
    }

    /**
     * Returns a copy of this context bound to the given update, sharing every service.
     *
     * @param update the incoming update
     * @return the derived context
     */
    public HandlerContext withUpdate(Update update)
    {
        return new HandlerContext(update, this.services);
    }

    /**
     * Returns the bot's own Telegram user id.
     *
     * @return the bot user id
     */
    public long botUserId()
    {
        return bot().id();
    }

    /**
     * Returns the bot's Telegram username without the leading '@'.
     *
     * @return the username, or an empty string when the bot has none
     */
    public String botUsername()
    {
        return bot().username();
    }

    /**
     * Returns the bot's display name.
     *
     * @return the display name
     */
    public String botName()
    {
        return bot().name();
    }

    /**
     * Returns the message this update carries, whichever field it arrived in.
     *
     * @return the message, or {@code null} when the update carries none
     */
    public Message message()
    {
        if (this.update == null)
        {
            return null;
        }

        if (this.update.hasMessage())
        {
            return this.update.getMessage();
        }

        if (this.update.hasEditedMessage())
        {
            return this.update.getEditedMessage();
        }

        if (this.update.hasChannelPost())
        {
            return this.update.getChannelPost();
        }

        if (this.update.hasEditedChannelPost())
        {
            return this.update.getEditedChannelPost();
        }

        if (this.update.hasBusinessMessage())
        {
            return this.update.getBusinessMessage();
        }

        if (this.update.hasEditedBusinessMessage())
        {
            return this.update.getEditedBuinessMessage();
        }

        if (this.update.hasCallbackQuery() && this.update.getCallbackQuery().getMessage() instanceof Message message)
        {
            return message;
        }

        return null;
    }

    /**
     * Returns the inline button press this update carries.
     *
     * @return the callback query, or {@code null} when the update carries none
     */
    public CallbackQuery callbackQuery()
    {
        return this.update != null && this.update.hasCallbackQuery() ? this.update.getCallbackQuery() : null;
    }

    /**
     * Returns the callback data of the inline button that was pressed.
     *
     * @return the callback data, or {@code null} when the update is not a button press
     */
    public String callbackData()
    {
        CallbackQuery query = callbackQuery();
        return query != null ? query.getData() : null;
    }

    /**
     * Returns the chat the update belongs to.
     *
     * @return the chat id, or {@code null} when the update is not bound to a chat
     */
    public Long chatId()
    {
        Message message = message();
        return message != null ? message.getChatId() : null;
    }

    /**
     * Returns the user who caused the update.
     *
     * @return the user, or {@code null} when the update has no author (a channel post, for instance)
     */
    public User user()
    {
        CallbackQuery query = callbackQuery();
        if (query != null)
        {
            return query.getFrom();
        }

        if (this.update != null && this.update.hasChatJoinRequest())
        {
            return this.update.getChatJoinRequest().getUser();
        }

        Message message = message();
        return message != null ? message.getFrom() : null;
    }

    /**
     * Returns the id of the user who caused the update.
     *
     * @return the user id, or {@code null} when the update has no author
     */
    public Long userId()
    {
        User user = user();
        return user != null ? user.getId() : null;
    }

    /**
     * Returns the invoked command name, lowercased, without the leading slash and without the
     * {@code @botname} suffix.
     *
     * @return the command name, or {@code null} when the update is not a command message
     */
    public String commandName()
    {
        if (this.update == null || !this.update.hasMessage())
        {
            return null;
        }

        String text = this.update.getMessage().getText();
        if (text == null)
        {
            return null;
        }

        String first = text.strip().split("\\s+", 2)[0];
        if (first.length() < 2 || first.charAt(0) != '/')
        {
            return null;
        }

        String command = first.substring(1);
        int at = command.indexOf('@');
        if (at >= 0)
        {
            command = command.substring(0, at);
        }

        return command.isEmpty() ? null : command.toLowerCase();
    }

    /**
     * Returns the text following the command token.
     *
     * @return the payload, trimmed, or {@code null} when the command carries none
     */
    public String commandPayload()
    {
        if (this.update == null || !this.update.hasMessage())
        {
            return null;
        }

        String text = this.update.getMessage().getText();
        if (text == null)
        {
            return null;
        }

        String[] parts = text.strip().split("\\s+", 2);
        if (parts.length < 2)
        {
            return null;
        }

        String payload = parts[1].strip();
        return payload.isEmpty() ? null : payload;
    }
}
