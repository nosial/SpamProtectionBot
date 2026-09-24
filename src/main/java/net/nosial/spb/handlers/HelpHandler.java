package net.nosial.spb.handlers;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.enums.HelpPage;
import net.nosial.spb.enums.OperatorPage;
import net.nosial.spb.enums.SettingsPage;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
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
 * Handles the {@code /help} command and the subsequent help-menu callbacks.
 *
 * <p>The {@code /help} command sends the main help menu as a private message with inline category
 * buttons. Each button updates the message to show detailed information about a specific feature.
 * {@code help:<PAGE_NAME>} callbacks select a top-level page; {@code help:SETTINGS} and
 * {@code help:OPERATORS} open a sub-menu of their own topics, {@code help:SETTINGS:<SETTING>} and
 * {@code help:OPERATORS:<TOPIC>} select one of those topics, and {@code help:MAIN} returns to the
 * main menu from either sub-menu or topic. The help menu is only available in private chats.
 */
@UpdateHandler(value = {UpdateType.COMMAND, UpdateType.CALLBACK_QUERY}, commands = "help", callbackData = HelpHandler.CALLBACK_PREFIX + ":")
public final class HelpHandler extends Handler
{
    static final String CALLBACK_PREFIX = "help";

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
        if (message == null || !"private".equals(message.getChat().getType()))
        {
            return;
        }

        Language lang = resolveLanguage(context, message);
        String html = buildMainMenuHtml(context, lang);
        InlineKeyboardMarkup markup = buildMainMenuMarkup(context, lang);

        replyHtml(context, "help-main-menu", message, html, markup);
    }

    /**
     * Handles the processing of callback queries from Telegram messages. This method is responsible
     * for identifying the callback query data, determining the intended action, and updating the
     * message content and markup based on the action.
     *
     * @param context the context containing the update and supporting elements for handling the callback
     * @throws TelegramApiException if an error occurs while interacting with the Telegram Bot API
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

        String[] parts = data.split(":", 2);
        if (parts.length != 2 || !CALLBACK_PREFIX.equals(parts[0]))
        {
            answer(context, callbackQuery);
            return;
        }

        String action = parts[1];
        Message message = requireMessage(callbackQuery);
        if (message == null)
        {
            answer(context, callbackQuery);
            return;
        }

        Language lang = resolveLanguage(context, callbackQuery);
        if ("SETTINGS".equals(action))
        {
            editMessage(context, message, callbackQuery, buildSettingsMenuHtml(context, lang), buildSettingsMenuMarkup(context, lang));
        }
        else if (action != null && action.startsWith("SETTINGS:"))
        {
            SettingsPage page = SettingsPage.valueOf(action.substring("SETTINGS:".length()));
            editMessage(context, message, callbackQuery, buildSettingsFeatureHtml(context, lang, page), buildSettingsFeatureMarkup(context, lang));
        }
        else if ("OPERATORS".equals(action))
        {
            editMessage(context, message, callbackQuery, buildOperatorMenuHtml(context, lang), buildOperatorMenuMarkup(context, lang));
        }
        else if (action != null && action.startsWith("OPERATORS:"))
        {
            OperatorPage page = OperatorPage.valueOf(action.substring("OPERATORS:".length()));
            editMessage(context, message, callbackQuery, buildOperatorFeatureHtml(context, lang, page), buildOperatorFeatureMarkup(context, lang));
        }
        else if ("MAIN".equals(action))
        {
            editMessage(context, message, callbackQuery, buildMainMenuHtml(context, lang), buildMainMenuMarkup(context, lang));
        }
        else
        {
            HelpPage page = HelpPage.valueOf(action);
            editMessage(context, message, callbackQuery, buildCategoryHtml(context, lang, page), buildCategoryMarkup(context, lang));
        }

        answer(context, callbackQuery);
    }

    /**
     * Builds and returns the HTML representation of the main menu content based on the context and language provided.
     *
     * @param context the handler context containing data and utilities necessary for constructing the menu
     * @param lang the language to be used for localizing the main menu content
     * @return a String containing the main menu content formatted in HTML
     */
    static String buildMainMenuHtml(HandlerContext context, Language lang)
    {
        return HelpPage.MAIN.body(context.languages(), lang) + "\n\n<b>" + context.languages().get(lang, "help", "categories_header") + "</b>\n" + context.languages().get(lang, "help", "categories_body");
    }

    /**
     * Constructs an inline keyboard markup representing the main menu for a bot interface.
     * <p>
     * The main menu contains categories such as Secretary Mode, Chat Protection,
     * Settings, Federation Commands, and Operators. A button is created for each category,
     * and these buttons are grouped into rows of two.
     *
     * @param context the handler context providing contextual information for menu generation
     * @param lang the language in which the menu should be rendered
     * @return an InlineKeyboardMarkup object representing the main menu with interactive buttons
     */
    static InlineKeyboardMarkup buildMainMenuMarkup(HandlerContext context, Language lang)
    {
        List<HelpPage> categories = List.of(
                HelpPage.SECRETARY_MODE, HelpPage.CHAT_PROTECTION, HelpPage.SETTINGS,
                HelpPage.FEDERATION_COMMANDS, HelpPage.OPERATORS);

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (HelpPage page : categories)
        {
            buttons.add(button(page.title(context.languages(), lang), CALLBACK_PREFIX + ":" + page.name()));
        }
        return markup(rowsOfTwo(buttons).toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Constructs an HTML representation of a help category page, optionally including a title.
     *
     * @param context the context of the current handler, providing access to relevant data
     * @param lang the language to be used for retrieving localized content
     * @param page the help page containing the content and title to be rendered
     * @return the HTML string for the help category page, including the title if applicable
     */
    static String buildCategoryHtml(HandlerContext context, Language lang, HelpPage page)
    {
        String body = page.body(context.languages(), lang);
        if (body.startsWith("<b>"))
        {
            return body;
        }
        return "<b>" + page.title(context.languages(), lang) + "</b>\n\n" + body;
    }

    /**
     * Builds and returns an InlineKeyboardMarkup for the category menu,
     * including a "Back" button that navigates to the main menu.
     *
     * @param context the context containing necessary information for building the markup,
     *                such as language settings and callback data.
     * @param lang the language to localize the button text.
     * @return the constructed InlineKeyboardMarkup for the category menu.
     */
    static InlineKeyboardMarkup buildCategoryMarkup(HandlerContext context, Language lang)
    {
        return singleRowMarkup(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":MAIN"));
    }

    /**
     * Builds the HTML content for the settings menu based on the given context and language.
     *
     * @param context the HandlerContext providing access to the environment and language resources
     * @param lang the Language used to retrieve localized text for the settings menu
     * @return a String containing the HTML representation of the settings menu
     */
    static String buildSettingsMenuHtml(HandlerContext context, Language lang)
    {
        String body = context.languages().get(lang, "help_pages", "settings_body");
        if (body.startsWith("<b>"))
        {
            return body;
        }
        return "<b>" + context.languages().get(lang, "help_pages", "settings_title")
                + "</b>\n\n" + body;
    }

    /**
     * Constructs and returns an {@code InlineKeyboardMarkup} representing the settings menu.
     * <p>
     * This method dynamically generates a keyboard with buttons for navigating
     * through the settings pages, based on the available {@code SettingsPage} enum values.
     * It also includes a "Back" button that navigates back to the main menu.
     *
     * @param context the {@code HandlerContext} containing necessary data for generating the menu,
     *                such as localization and callback handling information
     * @param lang the {@code Language} specifying the localization language for button titles
     * @return an {@code InlineKeyboardMarkup} object representing the settings menu layout
     */
    static InlineKeyboardMarkup buildSettingsMenuMarkup(HandlerContext context, Language lang)
    {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (SettingsPage page : SettingsPage.values())
        {
            buttons.add(button(page.title(context.languages(), lang), CALLBACK_PREFIX + ":SETTINGS:" + page.name()));
        }
        buttons.add(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":MAIN"));
        return markup(rowsOfTwo(buttons).toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Builds and returns the HTML content for a settings feature page.
     * Constructs the content using the provided context, language,
     * and settings page data. The method ensures the title is enclosed
     * in bold tags if the body doesn't already start with a bold tag.
     *
     * @param context the handler context containing relevant state and resources
     * @param lang the language used for the settings page content
     * @param page the settings page object with title and body information
     * @return the constructed HTML string for the settings feature page
     */
    static String buildSettingsFeatureHtml(HandlerContext context, Language lang,
                                           SettingsPage page)
    {
        String body = page.body(context.languages(), lang);
        if (body.startsWith("<b>"))
        {
            return body;
        }
        return "<b>" + page.title(context.languages(), lang) + "</b>\n\n" + body;
    }

    /**
     * Builds and returns an {@link InlineKeyboardMarkup} for the settings feature.
     * This markup includes a single row with a "Back" button to navigate to the settings menu.
     *
     * @param context the {@link HandlerContext} providing context for the handler, including access
     *                to bot services, utilities, and localization resources.
     * @param lang the {@link Language} representing the user's language preference for localization.
     * @return an {@link InlineKeyboardMarkup} containing a "Back" button that navigates to the settings menu.
     */
    static InlineKeyboardMarkup buildSettingsFeatureMarkup(HandlerContext context, Language lang)
    {
        return singleRowMarkup(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":SETTINGS"));
    }

    /**
     * Builds an HTML representation of the operator menu for the specified context and language.
     *
     * @param context the handler context providing access to relevant data and state
     * @param lang the language to be used for constructing the menu
     * @return a string containing the HTML representation of the operator menu
     */
    static String buildOperatorMenuHtml(HandlerContext context, Language lang)
    {
        return buildCategoryHtml(context, lang, HelpPage.OPERATORS);
    }

    /**
     * Constructs an {@link InlineKeyboardMarkup} object representing the operator menu in a Telegram bot interface.
     * The menu includes buttons for each operator page and a "Back" button to return to the main menu.
     *
     * @param context The {@link HandlerContext} providing access to the bot's resources and methods.
     * @param lang The {@link Language} object representing the user's preferred language for interface localization.
     * @return An {@link InlineKeyboardMarkup} instance containing the operator menu layout.
     */
    static InlineKeyboardMarkup buildOperatorMenuMarkup(HandlerContext context, Language lang)
    {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (OperatorPage page : OperatorPage.values())
        {
            buttons.add(button(page.title(context.languages(), lang), CALLBACK_PREFIX + ":OPERATORS:" + page.name()));
        }
        buttons.add(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":MAIN"));
        return markup(rowsOfTwo(buttons).toArray(new InlineKeyboardRow[0]));
    }

    /**
     * Builds an HTML string representation of a specific operator feature page.
     *
     * @param context the context containing user session and application data
     * @param lang the language to localize the content
     * @param page the operator page object containing title and body information
     * @return an HTML-formatted string representing the operator feature page content
     */
    static String buildOperatorFeatureHtml(HandlerContext context, Language lang, OperatorPage page)
    {
        String body = page.body(context.languages(), lang);
        if (body.startsWith("<b>"))
        {
            return body;
        }
        return "<b>" + page.title(context.languages(), lang) + "</b>\n\n" + body;
    }

    /**
     * Builds an inline keyboard markup with buttons for the operator features menu.
     *
     * @param context the handler context containing necessary resources and state.
     * @param lang the language object representing the user's selected language.
     * @return an instance of InlineKeyboardMarkup with the operator features menu buttons.
     */
    static InlineKeyboardMarkup buildOperatorFeatureMarkup(HandlerContext context, Language lang)
    {
        return singleRowMarkup(button(context.languages().get(lang, "general", "back"), CALLBACK_PREFIX + ":OPERATORS"));
    }
}
