package net.nosial.spb.utilities;

import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.objects.Language;
import net.nosial.spb.handlers.LanguageHandler;
import net.nosial.spb.handlers.secretary.SecretarySettingsHandler;
import net.nosial.spb.objects.context.HandlerContext;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the bot's introduction screen for a private chat.
 *
 * <p>Two different paths land on the same screen: {@code /start} in a private chat, and returning
 * from the secretary settings menu. Keeping the rendering here means the two cannot drift apart,
 * and neither handler has to reach into the other's package to draw it.
 */
public final class StartScreen
{
    /**
     * Generates an HTML-formatted string representing the start screen content.
     * The content includes bot name, descriptions, help information, and server details if available.
     *
     * @param context The context of the current handler, containing information about the bot and its settings.
     * @param lang The language in which the content should be localized.
     * @return A string containing the HTML-formatted content created based on the bot and server information.
     */
    public static String html(HandlerContext context, Language lang)
    {
        StringBuilder html = new StringBuilder();
        html.append("<b>").append(HtmlEscape.escape(context.botName())).append("</b>\n\n");
        html.append(context.languages().get(lang, "start", "private_description"));
        html.append(context.languages().get(lang, "start", "getting_started_header"));
        html.append(context.languages().get(lang, "start", "getting_started_private_body"));
        html.append(context.languages().get(lang, "start", "additional_help_header"));
        html.append(context.languages().get(lang, "start", "additional_help_body"));

        ServerInformation serverInformation = MessageHelper.serverInformation(context);
        if (serverInformation != null)
        {
            html.append(context.languages().get(lang, "start", "server_information_header"))
                    .append(context.languages().get(lang, "start", "server_name_label", HtmlEscape.escape(serverInformation.serverName())))
                    .append(context.languages().get(lang, "start", "server_host_label", HtmlEscape.escape(MessageHelper.serverHost(context))))
                    .append(context.languages().get(lang, "start", "server_api_version_label", HtmlEscape.escape(serverInformation.apiVersion())));
        }
        return html.toString();
    }

    /**
     * Generates an InlineKeyboardMarkup based on the provided context, message, and language.
     * It extracts the user ID from the message, if available, and uses it to delegate to another
     * markup generation method.
     *
     * @param context The context of the current handler, containing information about the bot and its settings.
     * @param message The message object containing user and other relevant data.
     * @param lang The language in which the InlineKeyboardMarkup should be localized.
     * @return An InlineKeyboardMarkup object created based on the provided parameters.
     */
    public static InlineKeyboardMarkup markup(HandlerContext context, Message message, Language lang)
    {
        long userId = message.getFrom() != null ? message.getFrom().getId() : 0;
        return markup(context, lang, userId);
    }

    /**
     * Generates an InlineKeyboardMarkup for the start screen based on the specified context, language, and user ID.
     * The markup includes buttons for adding the bot to a group, setting the language,
     * and accessing secretary settings if applicable.
     *
     * @param context The context of the current handler, containing information about the bot and its settings.
     * @param lang The language in which the buttons should be localized.
     * @param userId The unique identifier of the user for checking additional configurations.
     * @return An InlineKeyboardMarkup object containing the rows of inline keyboard buttons.
     */
    public static InlineKeyboardMarkup markup(HandlerContext context, Language lang, long userId)
    {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> primary = new ArrayList<>();

        if (!context.botUsername().isBlank())
        {
            primary.add(MessageHelper.urlButton(context.languages().get(lang, "buttons", "add_to_group"),
                    "https://t.me/" + context.botUsername() + "?startgroup=add"));
        }

        primary.add(MessageHelper.button(context.languages().get(lang, "buttons", "set_language"), LanguageHandler.CALLBACK_PREFIX + ":start_menu"));
        rows.add(new InlineKeyboardRow(primary));

        if (userId != 0 && context.managers().secretaryConfigurations().secretaryConfigurationExists(userId))
        {
            rows.add(new InlineKeyboardRow(MessageHelper.button(
                    context.languages().get(lang, "buttons", "secretary_settings"),
                    SecretarySettingsHandler.OPEN_WITH_BACK_CALLBACK)));
        }

        return MessageHelper.markup(rows.toArray(new InlineKeyboardRow[0]));
    }
}
