package net.nosial.spb.handlers;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import java.util.ArrayList;
import java.util.List;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.handlers.group.ConfigurationHandler;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.ConfigurationContext;
import net.nosial.spb.utilities.StartScreen;
import net.nosial.spb.utilities.HtmlEscape;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * The {@code /start} command handler.
 *
 * <p>In private chats the handler shows a getting-started message with an {@code Add to Group}
 * button that uses the {@code startgroup} deep link to prompt the user to add the bot to a group,
 * as well as a {@code Set Language} button that opens the language selection.
 *
 * <p>In group chats {@code /start} is answered non-ephemerally and the content depends on who
 * called it:
 * <ul>
 *     <li>Change-information administrators see the getting-started paragraph and a
 *     {@code Configure} button linking to a private configuration session; the chat owner also
 *     sees a {@code Set Language} button.</li>
 *     <li>Other members only see the bot's own information, without the getting-started paragraph
 *     or any buttons.</li>
 *     <li>With a payload (from a {@code startgroup} deep link) the bot was just added to the
 *     group and the configuration menu is opened immediately when the bot is a change-information
 *     administrator.</li>
 * </ul>
 *
 * <p>All display strings are resolved at runtime from the bot's own identity and the Federation
 * client instance (server name, host, and API version); nothing is hard-coded. Reply failures are
 * logged and swallowed, never propagated.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "start")
public final class StartHandler extends Handler
{
    @Override
    public boolean accepts(HandlerContext context)
    {
        // A forwarded /start is somebody quoting the command, not invoking it.
        Message message = context.update().getMessage();
        return message != null
                && message.getForwardOrigin() == null
                && message.getForwardFrom() == null
                && message.getForwardFromChat() == null;
    }

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        Language lang = resolveLanguage(context, message);
        switch (message.getChat().getType())
        {
            case "private":
                handlePrivateStart(context, message, lang);
                break;

            case "group":
            case "supergroup":
                handleGroupStart(context, message, lang);
                break;

            case "channel":
                replyHtml(context, "start-channel", message,
                        context.languages().get(lang, "start", "channel_message"), null);
                break;
        }
    }

    /**
     * Handles {@code /start} in a private chat.
     *
     * <p>Sends a getting-started message with an {@code Add to Group} button that links to the
     * bot's {@code startgroup} deep link, prompting the user to select a group to add the bot to.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /start} message
     * @throws TelegramApiException if a reply cannot be sent
     */
    private static void handlePrivateStart(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        String payload = getMessagePayload(message);
        if (payload != null)
        {
            ConfigurationContext session = context.sessions().configuration().find(payload);
            if (session == null || message.getFrom() == null || session.userId() != message.getFrom().getId())
            {
                replyHtml(context, "start-session-expired", message, context.languages().get(lang, "start", "session_expired_private"), null);
                return;
            }
            ConfigurationHandler.openMainMenu(context, session);
            return;
        }

        String html = StartScreen.html(context, lang
        );

        InlineKeyboardMarkup markup = StartScreen.markup(context, message, lang);

        replyHtml(context, "private-getting-started", message, html, markup);
    }

    /**
     * Handles {@code /start} inside a group or supergroup.
     *
     * <p>{@code /start} is answered non-ephemerally with the bot's summary. The summary and its
     * inline buttons depend on who called it: change-information administrators see the
     * getting-started paragraph and a {@code Configure} button linking to a private configuration
     * session; other members see only the bot's own information. When a payload is present (from
     * a {@code startgroup} deep link) the bot was just added to the group and the configuration
     * menu is opened immediately.
     * link) the bot was just added to the group and the configuration menu is opened immediately.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /start} message
     * @throws TelegramApiException if a reply cannot be sent
     */
    private static void handleGroupStart(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        // Checked against Telegram rather than the cache: /start is typically sent right after the
        // bot is added or promoted, exactly when a cached administrator list is out of date.
        refreshAdministrators(context, message.getChatId());

        if (getMessagePayload(message) != null)
        {
            handleStartGroupJoin(context, message, lang);
            return;
        }

        boolean administrator = isChangeInformationAdministrator(context, message);

        boolean configurable = administrator && isBotChangeInformationAdministrator(context, message.getChatId());
        ConfigurationContext session = null;
        if (configurable && message.getFrom() != null)
        {
            session = context.sessions().configuration().create(message.getFrom().getId(), message.getChatId());
        }

        sendGettingStarted(context, message, session, lang, administrator);
    }

    /**
     * Handles {@code /start} with a payload in a group chat, indicating the bot was added via a
     * {@code startgroup} deep link.
     *
     * <p>If the bot is a change-information administrator, a configuration session is created and
     * the settings menu is opened immediately. Otherwise the user is prompted to promote the bot
     * and run {@code /start} again.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /start} message
     * @throws TelegramApiException if a reply cannot be sent
     */
    private static void handleStartGroupJoin(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        if (!isBotChangeInformationAdministrator(context, message.getChatId()))
        {
            sendEphemeralHtml(context, message,
                    context.languages().get(lang, "start", "startgroup_promote"));
            return;
        }

        if (message.getFrom() == null)
        {
            return;
        }

        ConfigurationContext session = context.sessions().configuration().create(message.getFrom().getId(), message.getChatId());
        ConfigurationHandler.openMainMenu(context, session);
    }

    /**
     * Returns the getting-started inline markup for a group {@code /start} reply. An administrator
     * gets a single {@code Configure} button.
     *
     * @return the inline markup, or {@code null} when the bot has no username to link to
     */
    private static InlineKeyboardMarkup buildGettingStartedMarkup(HandlerContext context, ConfigurationContext session, Language lang)
    {
        if (context.botUsername().isBlank())
        {
            return null;
        }
        String base = "https://t.me/" + context.botUsername() + "?start=" + session.hash();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(urlButton(context.languages().get(lang, "buttons", "configure"), base)));
        return markup(rows.toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Sends the getting-started HTML message as a regular group message, with every display string
     * resolved from the bot's own identity and the Federation client instance.
     *
     * <p>The content depends on who called {@code /start}: change-information administrators see
     * the getting-started paragraph (either the configurable variant when {@code session} is
     * non-{@code null}, or the permissions variant otherwise) and a {@code Configure} button;
     * ordinary members see only the bot's own information and no inline buttons.
     *
     * @param context the per-update command context
     * @param message the incoming {@code /start} message
     * @param session the configuration session, or {@code null}
     * @param lang the resolved language of the message
     * @param administrator whether the author is a change-information administrator
     * @throws TelegramApiException if the summary cannot be sent
     */
    private static void sendGettingStarted(HandlerContext context, Message message, ConfigurationContext session, Language lang, boolean administrator)
            throws TelegramApiException
    {
        boolean configurable = session != null;
        StringBuilder html = new StringBuilder();
        html.append("<b>").append(HtmlEscape.escape(context.botName())).append("</b>\n\n");
        html.append(context.languages().get(lang, "start", "private_description"));

        if (administrator)
        {
            html.append(context.languages().get(lang, "start", "getting_started_header"));
            if (configurable)
            {
                html.append(context.languages().get(lang, "start", "getting_started_group_configurable"));
            }
            else
            {
                html.append(context.languages().get(lang, "start", "getting_started_group_permissions"));
            }
        }

        html.append(context.languages().get(lang, "start", "additional_help_header"));
        html.append(context.languages().get(lang, "start", "additional_help_body"));

        ServerInformation serverInformation = serverInformation(context);
        if (serverInformation != null)
        {
            html.append(context.languages().get(lang, "start", "server_information_header"))
                    .append(context.languages().get(lang, "start", "server_name_label",
                            HtmlEscape.escape(serverInformation.serverName())))
                    .append(context.languages().get(lang, "start", "server_host_label",
                            HtmlEscape.escape(serverHost(context))))
                    .append(context.languages().get(lang, "start", "server_api_version_label",
                            HtmlEscape.escape(serverInformation.apiVersion())));
        }

        if (serverInformation != null)
        {
            html.append(context.languages().get(lang, "start", "server_footer",
                    HtmlEscape.escape(serverInformation.serverName()),
                    HtmlEscape.escape(serverInformation.apiVersion())));
        }

        var builder = SendMessage.builder().chatId(String.valueOf(message.getChatId()))
                .messageThreadId(message.getMessageThreadId())
                .text(html.toString())
                .parseMode(ParseMode.HTML);
        if (configurable)
        {
            InlineKeyboardMarkup markup = buildGettingStartedMarkup(context, session, lang);
            if (markup != null)
            {
                builder.replyMarkup(markup);
            }
        }

        execute(context, "getting-started", builder.build());
    }
}
