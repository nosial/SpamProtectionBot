package net.nosial.spb.handlers;

import net.nosial.spb.utilities.StartScreen;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code /language} and {@code /lang} commands and the language-selection callbacks.
 *
 * <p>In private chats the command shows the current language and inline flag buttons for the user
 * to switch; in group chats only administrators who can change the group's information may change
 * the language, checked against Telegram on both the command and each button press, and the menu is shown as
 * an ephemeral message visible only to the caller.
 *
 * <p>{@code lang:<language_code>} callbacks persist the new language preference and refresh the
 * menu, while the special {@code lang:menu} and {@code lang:start} actions re-open the language
 * selection or return to the private {@code /start} menu.
 */
@UpdateHandler(value = {UpdateType.COMMAND, UpdateType.CALLBACK_QUERY}, commands = {"language", "lang"}, callbackData = LanguageHandler.CALLBACK_PREFIX + ":")
public final class LanguageHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageHandler.class);

    /**
     * The callback namespace for the language picker.
     *
     * <p>Public because the start screen puts a Set Language button on its own message, which
     * means the namespace is a contract between the two rather than an internal detail.
     */
    public static final String CALLBACK_PREFIX = "lang";

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Update update = context.update();
        if (update.hasCallbackQuery())
        {
            handleCallback(context);
            return;
        }

        Message message = update.getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        long chatId = message.getChatId();
        long userId = message.getFrom().getId();
        Language currentLanguage;
        boolean isGroup = isGroupChat(message);

        if (isGroup)
        {
            refreshAdministrators(context, chatId);
            if (!isChangeInformationAdministrator(context, message))
            {
                Language userLang = context.managers().languagePreferences().getUserLanguage(userId);
                String errorText = context.languages().get(userLang, "general", "language.group_admin_only");
                sendHtml(context, message, errorText, true, null);
                return;
            }
            currentLanguage = context.managers().languagePreferences().getChatLanguage(chatId);
        }
        else
        {
            currentLanguage = context.managers().languagePreferences().getUserLanguage(userId);
        }

        String html = context.languages().get(currentLanguage, "general", "language.select")
                + "\n\n" + context.languages().get(currentLanguage, "general", "language.current",
                currentLanguage.emoji(), currentLanguage.name());
        InlineKeyboardMarkup markup = isGroup
                ? buildLanguageMarkup(context)
                : buildPrivateLanguageMarkup(context, currentLanguage, false);
        if (isGroup)
        {
            sendHtml(context, message, html, true, markup);
        }
        else
        {
            replyHtml(context, "language-menu", message, html, markup);
        }
    }

    /**
     * Handles incoming callback queries and performs the necessary actions based on the provided data.
     * The method processes the callback data, updates the message content and UI components,
     * and adjusts user or group language preferences accordingly.
     *
     * @param context the {@link HandlerContext} object providing context for the current update,
     *                including the callback query, language settings, and helper methods.
     * @throws TelegramApiException if there is an error while interacting with the Telegram Bot API.
     */
    private void handleCallback(HandlerContext context) throws TelegramApiException
    {
        CallbackQuery callbackQuery = context.update().getCallbackQuery();
        String data = callbackQuery.getData();
        if (data == null)
        {
            answer(context, callbackQuery);
            return;
        }

        String[] parts = data.split(":", 3);
        if (parts.length < 2 || !CALLBACK_PREFIX.equals(parts[0]))
        {
            answer(context, callbackQuery);
            return;
        }

        Language language = context.languages().resolve(parts[1]);
        if (language == null && !"start".equals(parts[1]) && !"start_menu".equals(parts[1]) && !"menu".equals(parts[1]))
        {
            // A button from an older menu may name a language that is no longer shipped.
            answer(context, callbackQuery);
            return;
        }
        boolean returnToStart = parts.length == 3 && "start".equals(parts[2]);
        Message message = requireMessage(callbackQuery);
        if (message == null)
        {
            answer(context, callbackQuery);
            return;
        }

        if ("start".equals(parts[1]))
        {
            if (!"private".equals(message.getChat().getType()))
            {
                answer(context, callbackQuery);
                return;
            }

            Language current = resolveLanguage(context, callbackQuery);
            editMessage(context, message, callbackQuery, StartScreen.html(context, current), StartScreen.markup(context, current, callbackQuery.getFrom().getId()));
            answer(context, callbackQuery);
            return;
        }

        if ("start_menu".equals(parts[1]))
        {
            if (!"private".equals(message.getChat().getType()))
            {
                answer(context, callbackQuery);
                return;
            }
            Language current = resolveLanguage(context, callbackQuery);
            String html = context.languages().get(current, "general", "language.select")
                    + "\n\n" + context.languages().get(current, "general", "language.current",
                    current.emoji(), current.name());
            editMessage(context, message, callbackQuery, html, buildPrivateLanguageMarkup(context, current, true));
            answer(context, callbackQuery);
            return;
        }

        if ("menu".equals(parts[1]))
        {
            Language current = resolveLanguage(context, callbackQuery);
            String html = context.languages().get(current, "general", "language.select")
                    + "\n\n" + context.languages().get(current, "general", "language.current",
                    current.emoji(), current.name());
            editMessage(context, message, callbackQuery, html, buildLanguageMarkup(context));
            answer(context, callbackQuery);
            return;
        }

        long chatId = message.getChatId();
        long userId = callbackQuery.getFrom() != null ? callbackQuery.getFrom().getId() : 0;

        boolean isGroup = chatId < 0;
        if (isGroup)
        {
            refreshAdministrators(context, chatId);
            if (!isChangeInformationAdministrator(context, chatId, userId))
            {
                answerAlert(context, callbackQuery, context.languages().get(resolveLanguage(context, callbackQuery),
                        "general", "language.group_admin_only"));
                return;
            }
        }

        try
        {
            if (isGroup)
            {
                context.managers().languagePreferences().setChatLanguage(chatId, language);
            }
            else
            {
                context.managers().languagePreferences().setUserLanguage(userId, language);
            }
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to save language preference: {}", e.getMessage());
            answerAlert(context, callbackQuery, context.languages().get(language,
                    "general", "error.occurred"));
            return;
        }

        String html = context.languages().get(language, "general", "language.changed",
                language.emoji(), language.name())
                + "\n\n" + context.languages().get(language, "general", "language.select")
                + "\n\n" + context.languages().get(language, "general", "language.current",
                language.emoji(), language.name());
        InlineKeyboardMarkup markup = !isGroup
                ? buildPrivateLanguageMarkup(context, language, returnToStart)
                : buildLanguageMarkup(context);
        editMessage(context, message, callbackQuery, html, markup);
        answer(context, callbackQuery, context.languages().get(language, "general", "language.changed", language.emoji(), language.name()));
    }

    /**
     * Constructs an {@link InlineKeyboardMarkup} instance to represent a language selection menu.
     * This menu can optionally include a "back" button, depending on the provided parameters.
     *
     * @param context the {@link HandlerContext} providing access to bot configuration and utilities
     * @param lang the {@link Language} representing the user's current language setting
     * @param fromStart a boolean flag indicating whether the menu should include a "back" button,
     *                  used when navigating from the start of the flow
     * @return an {@link InlineKeyboardMarkup} object containing the configured language selection menu
     */
    static InlineKeyboardMarkup buildPrivateLanguageMarkup(HandlerContext context, Language lang, boolean fromStart)
    {
        List<InlineKeyboardRow> rows = buildLanguageRows(context, fromStart ? ":start" : "");
        if (fromStart)
        {
            rows.add(new InlineKeyboardRow(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":start")));
        }
        return markup(rows.toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Constructs an {@link InlineKeyboardMarkup} containing language selection rows
     * based on the provided handler context.
     *
     * @param context the {@link HandlerContext} providing data and resources relevant
     *                to the current operation.
     * @return an {@link InlineKeyboardMarkup} representing the language selection UI.
     */
    static InlineKeyboardMarkup buildLanguageMarkup(HandlerContext context)
    {
        return markup(buildLanguageRows(context).toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Builds a list of inline keyboard rows representing language options.
     *
     * @param context the context containing necessary information for building the rows
     * @return a list of inline keyboard rows representing the available languages
     */
    static List<InlineKeyboardRow> buildLanguageRows(HandlerContext context)
    {
        return buildLanguageRows(context, "");
    }

    /**
     * Builds a list of inline keyboard rows for language selection.
     * Each row contains up to 4 inline keyboard buttons representing available languages.
     *
     * @param context the handler context providing access to available languages
     * @param callbackSuffix the suffix to be added to the callback data for each button
     * @return a list of inline keyboard rows, each containing buttons for a subset of available languages
     */
    private static List<InlineKeyboardRow> buildLanguageRows(HandlerContext context, String callbackSuffix)
    {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Language language : context.languages().availableLanguages())
        {
            buttons.add(languageButton(language, callbackSuffix));
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int index = 0; index < buttons.size(); index += 4)
        {
            rows.add(new InlineKeyboardRow(
                    buttons.subList(index, Math.min(index + 4, buttons.size()))));
        }
        return rows;
    }

    /**
     * Creates an inline keyboard button for a given language.
     *
     * @param language the language for which the button is created; its emoji() method defines the label, and its code() method is used in callback data
     * @param callbackSuffix the suffix to append to the callback data for identification
     * @return an InlineKeyboardButton representing the language with corresponding callback data
     */
    private static InlineKeyboardButton languageButton(Language language, String callbackSuffix)
    {
        return button(language.emoji(), CALLBACK_PREFIX + ":" + language.code() + callbackSuffix);
    }
}
