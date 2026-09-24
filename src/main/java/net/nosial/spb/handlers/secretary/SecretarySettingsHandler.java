package net.nosial.spb.handlers.secretary;
import net.nosial.spb.handlers.secretary.SecretaryMessageHandler.ContactDecision;
import net.nosial.spb.utilities.EntityInfoRenderer;
import net.nosial.spb.utilities.StartScreen;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.context.ConfigurationContext;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import net.nosial.spb.objects.database.SecretaryContact;
import org.slf4j.Logger;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles inline keyboard callbacks for the secretary settings menu.
 *
 * <p>Callbacks update secretary behavior or the owner’s per-connection contact decision.
 */
@UpdateHandler(value = {UpdateType.COMMAND, UpdateType.CALLBACK_QUERY}, commands = {"start", "settings"}, callbackData = {SecretarySettingsHandler.CALLBACK_PREFIX + ":", SecretarySettingsHandler.CONTACT_CALLBACK_PREFIX + ":", SecretarySettingsHandler.CONTACT_SETTINGS_CALLBACK_PREFIX + ":"}, priority = 100)
public final class SecretarySettingsHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretarySettingsHandler.class);
    // Package-private: the class-level @UpdateHandler annotation reads it.
    static final String CALLBACK_PREFIX = "secset";
    static final String OPEN_CALLBACK = CALLBACK_PREFIX + ":open";
    /**
     * Opens the secretary menu with a Back button that returns to the {@code /start} screen.
     *
     * <p>Public because the start screen puts this button on its own message: it is the contract
     * between the two, not an internal detail.
     */
    public static final String OPEN_WITH_BACK_CALLBACK = CALLBACK_PREFIX + ":openfromstart";
    static final String CONTACT_CALLBACK_PREFIX = "seccontact";
    static final String CONTACT_SETTINGS_CALLBACK_PREFIX = "seccontactsettings";

    /** The deep-link payload Telegram uses for the "Manage Bot" button in business chats. */
    private static final String BUSINESS_START_PAYLOAD_PREFIX = "bizChat";


    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Update update = context.update();
        if (!update.hasCallbackQuery())
        {
            handleCommand(context, update.getMessage());
            return;
        }

        CallbackQuery callbackQuery = update.getCallbackQuery();
        String data = callbackQuery.getData();
        if (data == null)
        {
            answer(context, callbackQuery);
            return;
        }

        if (data.startsWith(CONTACT_CALLBACK_PREFIX + ":")
                || data.startsWith(CONTACT_SETTINGS_CALLBACK_PREFIX + ":"))
        {
            handleContactDecision(context, callbackQuery, data);
            return;
        }

        if (OPEN_CALLBACK.equals(data) || OPEN_WITH_BACK_CALLBACK.equals(data))
        {
            openFromStart(context, callbackQuery, OPEN_WITH_BACK_CALLBACK.equals(data));
            return;
        }
        String[] parts = data.split(":", 3);
        if (parts.length != 3 || !CALLBACK_PREFIX.equals(parts[0]))
        {
            answer(context, callbackQuery);
            return;
        }

        String hash = parts[1];
        String action = parts[2];

        ConfigurationContext session = context.sessions().configuration().find(hash);
        if (session == null || callbackQuery.getFrom() == null || session.userId() != callbackQuery.getFrom().getId())
        {
            answer(context, callbackQuery);
            editToExpired(context, callbackQuery);
            return;
        }

        Message message = requireMessage(callbackQuery);
        if (message == null)
        {
            answer(context, callbackQuery);
            return;
        }

        SecretaryConfiguration configuration;

        if (action.equals("start"))
        {
            Language lang = sessionLanguage(context, session.userId());
            editMessage(context, message, callbackQuery, StartScreen.html(context, lang), StartScreen.markup(context, lang, callbackQuery.getFrom().getId()));
            answer(context, callbackQuery);
            return;
        }
        else
        {
            if (!action.startsWith("set_behavior:"))
            {
                LOGGER.warn("Unknown secretary settings action '{}'", action);
                answer(context, callbackQuery);
                return;
            }

            String requested = action.substring("set_behavior:".length());
            if (!"PASSIVE".equals(requested) && !"STRICT".equals(requested))
            {
                LOGGER.warn("Unsupported secretary behavior '{}'", requested);
                answer(context, callbackQuery);
                return;
            }
            setBehavior(context, session.userId(), ScanningBehavior.valueOf(requested));
        }

        configuration = context.managers().secretaryConfigurations().resolve(session.userId());
        Language lang = sessionLanguage(context, session.userId());
        editMessage(context, message, callbackQuery, menuHtml(context, configuration, lang), menuMarkup(context, configuration, session, lang, session.showBackButton()));
        answer(context, callbackQuery, context.languages().get(lang, "secretary_settings", "toast_behavior_changed", behaviorDisplayName(context.languages(), lang, configuration.behavior())));
    }

    @Override
    public boolean accepts(HandlerContext context)
    {
        if (context.update().hasCallbackQuery())
        {
            return true;
        }

        // Secretary mode is a private-chat feature, and it only claims /start when the deep link
        // says the user arrived from their business account; every other /start belongs to
        // StartHandler, which this handler outranks.
        Message message = context.update().getMessage();
        if (message == null || !isPrivateChat(message))
        {
            return false;
        }

        if ("start".equals(context.commandName()))
        {
            String payload = getMessagePayload(message);
            return payload != null && payload.startsWith(BUSINESS_START_PAYLOAD_PREFIX);
        }

        return true;
    }

    /**
     * Handles the processing of commands sent by a user in a message.
     *
     * @param context the current handler context containing necessary resources and state
     * @param message the message received from the user containing the command to process
     * @throws TelegramApiException if an error occurs during interaction with the Telegram API
     */
    private static void handleCommand(HandlerContext context, Message message) throws TelegramApiException
    {
        if (message == null || message.getFrom() == null)
        {
            return;
        }
        long userId = message.getFrom().getId();

        if (!context.managers().secretaryConfigurations().secretaryConfigurationExists(userId))
        {
            if (isCommand(context.update(), "settings"))
            {
                Language lang = resolveLanguage(context, message);
                sendReply(context, message, context.languages().get(lang, "settings", "secretary_not_enabled"));
                return;
            }

            if (enableFromBusinessDeepLink(context, userId))
            {
                sendContactDeepLinkView(context, message);
                return;
            }

            Language lang = resolveLanguage(context, message);
            sendReply(context, message, context.languages().get(lang, "secretary_settings", "not_enabled"));
            return;
        }

        if (isCommand(context.update(), "start"))
        {
            sendContactDeepLinkView(context, message);
            return;
        }

        ConfigurationContext session = context.sessions().configuration().create(userId, message.getChatId());
        openMenu(context, session);
    }

    /**
     * Sends a detailed view of a contact's deep link information to the user.
     * Displays contact-related details based on their presence and status and provides appropriate interaction options.
     *
     * @param context The handler context that provides utility methods, configurations, and managers necessary for processing.
     * @param message The message object containing information about the Telegram message, such as the sender and content.
     * @throws TelegramApiException If an error occurs during communication with the Telegram API.
     */
    private static void sendContactDeepLinkView(HandlerContext context, Message message) throws TelegramApiException
    {
        Language lang = resolveLanguage(context, message);
        LanguageManager lm = context.languages();
        Long contactId = businessContactId(message);

        if (contactId == null)
        {
            sendHtml(context, message, lm.get(lang, "secretary_settings", "contact_not_known"), false, openSettingsMarkup(lm, lang));
            return;
        }

        SecretaryConfiguration configuration = context.managers().secretaryConfigurations()
                .resolve(message.getFrom().getId());
        String connectionId = configuration.businessConnectionId();
        Optional<SecretaryContact> contact = connectionId == null || connectionId.isBlank() ? Optional.empty()
                : context.managers().secretaryContacts().getSecretaryContact(connectionId, contactId);

        StringBuilder html = new StringBuilder(lm.get(lang, "secretary_settings", "contact_title"));
        if (contact.isPresent())
        {
            String status = lm.get(lang, "secretary_settings", "contact_" + contact.get().status().name().toLowerCase());
            html.append(lm.get(lang, "secretary_settings", "contact_status", status));
        }
        else
        {
            html.append(lm.get(lang, "secretary_settings", "contact_not_known_hint"));
        }
        EntityInfoRenderer.appendContactInfo(context, html, contactId, lang);

        InlineKeyboardMarkup markup = connectionId != null && !connectionId.isBlank()
                ? SecretaryMessageHandler.contactSettingsDecisionMarkup(lm, connectionId, contactId, lang,
                contact.map(SecretaryContact::status).orElse(SecretaryContactStatus.UNKNOWN)) : openSettingsMarkup(lm, lang);
        sendHtml(context, message, html.toString(), false, markup);
    }

    /**
     * Creates and returns an inline keyboard markup for the "settings" UI.
     * <p>
     * This markup contains a single button that provides access to the "Contact Not Known" settings.
     *
     * @param lm   the LanguageManager instance used to retrieve localized strings
     * @param lang the Language object representing the current language for localization
     * @return an InlineKeyboardMarkup containing the settings button
     */
    private static InlineKeyboardMarkup openSettingsMarkup(LanguageManager lm, Language lang)
    {
        return singleRowMarkup(button(lm.get(lang, "secretary_settings", "contact_not_known_button"), OPEN_CALLBACK));
    }

    /**
     * Extracts and returns the business contact ID from the given message if it exists and is valid.
     * The method examines the payload of the message, checks if it starts with a defined business
     * payload prefix, and parses the remaining part as a long value. If the payload is null, does not
     * match the prefix, or cannot be parsed to a valid long value, the method returns {@code null}.
     *
     * @param message the message containing the potential business contact ID in its payload
     * @return the extracted business contact ID as a {@code Long}, or {@code null} if the ID is
     *         missing, invalid, or cannot be parsed
     */
    static Long businessContactId(Message message)
    {
        String payload = getMessagePayload(message);
        if (payload == null || !payload.startsWith(BUSINESS_START_PAYLOAD_PREFIX))
        {
            return null;
        }
        String chatId = payload.substring(BUSINESS_START_PAYLOAD_PREFIX.length());
        try
        {
            return chatId.isBlank() ? null : Long.parseLong(chatId);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * Enables secretary mode for a user from a business deep link.
     * This method attempts to create a secretary configuration for the specified user.
     *
     * @param context the {@code HandlerContext} providing access to application managers and services
     * @param userId the unique identifier of the user for whom the secretary mode is being enabled
     * @return {@code true} if the secretary mode was successfully enabled;
     *         {@code false} if an error occurred during the process
     */
    static boolean enableFromBusinessDeepLink(HandlerContext context, long userId)
    {
        try
        {
            context.managers().secretaryConfigurations().createSecretaryConfiguration(userId);
            return true;
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to enable secretary mode for user {} from the business deep link: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    /**
     * Opens the menu from the start in response to a callback query. It performs various
     * checks, manages session configurations, and updates the message with the appropriate
     * menu content and markup.
     *
     * @param context         the context of the handler which contains managers, sessions, and utilities
     * @param callbackQuery   the callback query received from the user triggering this action
     * @param showBackButton  flag indicating whether the back button should be displayed in the menu
     * @throws TelegramApiException if an error occurs while interacting with the Telegram API
     */
    private static void openFromStart(HandlerContext context, CallbackQuery callbackQuery, boolean showBackButton)
            throws TelegramApiException
    {
        Message message = requireMessage(callbackQuery);
        if (message == null || !"private".equals(message.getChat().getType()) || callbackQuery.getFrom() == null || !context.managers().secretaryConfigurations().secretaryConfigurationExists(callbackQuery.getFrom().getId()))
        {
            answer(context, callbackQuery);
            return;
        }

        ConfigurationContext session = context.sessions().configuration().create(
                callbackQuery.getFrom().getId(), message.getChatId());
        context.sessions().configuration().updateMessageId(session.hash(), message.getMessageId());
        context.sessions().configuration().updateShowBackButton(session.hash(), showBackButton);
        Language lang = sessionLanguage(context, session.userId());
        SecretaryConfiguration configuration = context.managers().secretaryConfigurations().resolve(session.userId());
        editMessage(context, message, callbackQuery, menuHtml(context, configuration, lang), menuMarkup(context, configuration, session, lang, showBackButton));
        answer(context, callbackQuery);
    }
    /**
     * Handles the decision callback for a contact within the secretary settings context.
     * Processes user actions to allow or deny contact access, updates the contact status
     * in the system, and modifies the Telegram interface accordingly.
     *
     * @param context The {@link HandlerContext} providing access to application dependencies
     *                and managers necessary for handling the request.
     * @param callbackQuery The callback query object received from Telegram, containing
     *                      details about the query initiated by the user.
     * @param data The callback query data string, which includes action identifiers and
     *             parameters (delimited by colons) to determine the specific contact decision logic.
     * @throws TelegramApiException When there is an error while interacting with the Telegram API
     *                               (e.g., sending messages or updating message layouts).
     */
    private static void handleContactDecision(HandlerContext context, CallbackQuery callbackQuery,
                                              String data) throws TelegramApiException
    {
        String[] parts = data.split(":", 4);
        Message message = requireMessage(callbackQuery);
        boolean settingsCallback = parts.length > 0 && CONTACT_SETTINGS_CALLBACK_PREFIX.equals(parts[0]);
        if (parts.length != 4
                || (!CONTACT_CALLBACK_PREFIX.equals(parts[0]) && !settingsCallback)
                || callbackQuery.getFrom() == null
                || (message != null && (message.getChat() == null
                || !"private".equals(message.getChat().getType()))))
        {
            answer(context, callbackQuery);
            return;
        }
        long callbackUserId = callbackQuery.getFrom().getId();
        Optional<SecretaryConfiguration> configuration = context.managers().secretaryConfigurations()
                .getSecretaryConfiguration(callbackUserId);
        if (configuration.isEmpty() || !parts[1].equals(configuration.get().businessConnectionId()))
        {
            LOGGER.warn("Rejected secretary contact callback for connection {} from user {}", parts[1], callbackUserId);
            answer(context, callbackQuery);
            return;
        }
        long contactId;
        try
        {
            contactId = Long.parseLong(parts[2]);
        }
        catch (NumberFormatException e)
        {
            answer(context, callbackQuery);
            return;
        }
        boolean allowed = "allow".equals(parts[3]);
        if (!allowed && !"deny".equals(parts[3]))
        {
            answer(context, callbackQuery);
            return;
        }
        Language lang = sessionLanguage(context, callbackUserId);
        try
        {
            context.managers().secretaryContacts().setSecretaryContactStatus(parts[1], contactId,
                    allowed ? SecretaryContactStatus.ALLOWED : SecretaryContactStatus.DENIED);
            if (message != null && message.getMessageId() != null && message.getChatId() != null)
            {
                SecretaryContactStatus updatedStatus = allowed
                        ? SecretaryContactStatus.ALLOWED : SecretaryContactStatus.DENIED;
                Optional<SecretaryContact> updatedContact = context.managers().secretaryContacts()
                        .getSecretaryContact(parts[1], contactId);
                if (settingsCallback && updatedContact.isPresent())
                {
                    context.telegramClient().execute(EditMessageText.builder()
                            .chatId(String.valueOf(message.getChatId()))
                            .messageId(message.getMessageId())
                            .text(contactMenuHtml(context, updatedContact.get(), lang))
                            .parseMode(ParseMode.HTML)
                            .replyMarkup(SecretaryMessageHandler.contactSettingsDecisionMarkup(
                                    context.languages(), parts[1], contactId, lang, updatedStatus))
                            .build());
                }
                else
                {
                    context.telegramClient().execute(EditMessageReplyMarkup.builder()
                            .chatId(String.valueOf(message.getChatId()))
                            .messageId(message.getMessageId())
                            .replyMarkup(SecretaryMessageHandler.contactDecisionMarkup(
                                    context.languages(),
                                    new ContactDecision(parts[1], contactId, lang,
                                            updatedStatus)))
                            .build());
                }
            }
            answer(context, callbackQuery, context.languages().get(lang, "secretary_settings", allowed ? "toast_contact_allowed" : "toast_contact_denied"));
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to {} secretary contact {} in connection {}: {}", allowed ? "allow" : "deny", contactId, parts[1], e.getMessage());
            answerAlert(context, callbackQuery, context.languages().get(lang, "general", "error.occurred"));
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Failed to remove secretary decision buttons for contact {} in connection {}: {}", contactId, parts[1], e.getMessage());
            answerAlert(context, callbackQuery, context.languages().get(lang, "general", "error.occurred"));
        }
    }
    /**
     * Generates an HTML string representing the contact menu for a secretary's contact settings.
     * <p>
     * This method retrieves localized strings from the language manager and constructs an HTML
     * representation of the contact's status and details. It also appends rendered contact information.
     *
     * @param context The handler context that provides access to managers and resources.
     * @param contact The contact whose menu information is being generated.
     * @param lang The language in which the menu should be generated.
     * @return A string containing the HTML representation of the contact menu.
     */
    static String contactMenuHtml(HandlerContext context, SecretaryContact contact, Language lang)
    {
        LanguageManager lm = context.languages();
        String status = lm.get(lang, "secretary_settings", "contact_" + contact.status().name().toLowerCase());
        StringBuilder html = new StringBuilder(lm.get(lang, "secretary_settings", "contact_title"));
        html.append(lm.get(lang, "secretary", "notification_contact", NotificationFormatter.userMention(contact.id(), context.managers().users(), lang)));
        html.append(lm.get(lang, "secretary_settings", "contact_status", status));
        EntityInfoRenderer.appendContactInfo(context, html, contact.id(), lang);
        return html.toString();
    }

    /**
     * Opens the settings menu for the user in a private chat, allowing interaction with secretary settings.
     * (Entry Point)
     *
     * @param context the handler context containing managers and utilities necessary for processing the request
     * @param session the configuration session context that holds user-specific data used for generating the menu
     * @throws TelegramApiException if an error occurs during the process of sending a message or interacting with the Telegram API
     */
    public static void openMenu(HandlerContext context, ConfigurationContext session) throws TelegramApiException
    {
        SecretaryConfiguration configuration = context.managers().secretaryConfigurations().resolve(session.userId());
        Language lang = sessionLanguage(context, session.userId());
        Message sent = execute(context, "open-secretary-settings", SendMessage.builder()
                .chatId(String.valueOf(session.userId()))
                .text(menuHtml(context, configuration, lang))
                .parseMode(ParseMode.HTML)
                .replyMarkup(menuMarkup(context, configuration, session, lang, false))
                .build());
        context.sessions().configuration().updateMessageId(session.hash(), sent.getMessageId());
    }

    /**
     * Generates the HTML content for the secretary settings menu.
     *
     * @param context the handler context containing necessary data for processing
     * @param configuration the configuration object for the secretary settings
     * @param lang the language to be used for localizing the menu content
     * @return a string representing the HTML content of the secretary settings menu
     */
    private static String menuHtml(HandlerContext context, SecretaryConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        return lm.get(lang, "secretary_settings", "title") + lm.get(lang, "secretary_settings", "description") + "\n\n" +
                lm.get(lang, "secretary_settings", "behavior", behaviorSelection(lm, lang, configuration)) + '\n';
    }

    /**
     * Generates a string representation of a behavior by combining its display name and description.
     *
     * @param lm the LanguageManager instance used for retrieving localized text
     * @param lang the language object specifying the language context
     * @param configuration the configuration object containing behavior details
     * @return a string combining the localized behavior display name and description, separated by an em dash
     */
    private static String behaviorSelection(LanguageManager lm, Language lang, SecretaryConfiguration configuration)
    {
        return behaviorDisplayName(lm, lang, configuration.behavior()) + " — " + behaviorDescription(lm, lang, configuration.behavior());
    }

    /**
     * Creates and returns an inline keyboard markup to render a menu for changing scanning behaviors
     * and optionally including a "back" button based on the provided parameters.
     *
     * @param context the handler context containing dependencies and resources for managing language and sessions
     * @param configuration the current secretary configuration specifying the active scanning behavior
     * @param session the configuration context for maintaining session-specific data
     * @param lang the selected language for generating localized labels for the menu elements
     * @param showBackButton a flag indicating whether to include a "back" button in the menu
     * @return an {@code InlineKeyboardMarkup} object representing the constructed menu
     */
    private static InlineKeyboardMarkup menuMarkup(HandlerContext context, SecretaryConfiguration configuration, ConfigurationContext session, Language lang, boolean showBackButton)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardButton> behaviorButtons = new ArrayList<>();
        for (ScanningBehavior behavior : new ScanningBehavior[]{ScanningBehavior.PASSIVE, ScanningBehavior.STRICT})
        {
            if (behavior != configuration.behavior())
            {
                behaviorButtons.add(actionButton(session, "set_behavior:" + behavior.name(),
                        behaviorDisplayName(lm, lang, behavior)));
            }
        }

        List<InlineKeyboardRow> rows = new ArrayList<>(rowsOfTwo(behaviorButtons));
        if (showBackButton)
        {
            rows.add(new InlineKeyboardRow(actionButton(session, "start", lm.get(lang, "general", "back"))));
        }

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Creates an inline keyboard button for a specified action and label.
     *
     * @param session the configuration context associated with the current user session
     * @param action the specific action identifier to be associated with the button
     * @param label the display text for the button
     * @return an {@link InlineKeyboardButton} configured with the specified label and action
     */
    private static InlineKeyboardButton actionButton(ConfigurationContext session, String action, String label)
    {
        return button(label, CALLBACK_PREFIX + ":" + session.hash() + ":" + action);
    }


    /**
     * Retrieves the display name for a given scanning behavior based on the specified language.
     *
     * @param lm       The LanguageManager instance used to fetch localized strings.
     * @param lang     The language in which the display name should be retrieved.
     * @param behavior The scanning behavior for which the display name is required.
     * @return A localized string representing the display name of the specified scanning behavior.
     */
    private static String behaviorDisplayName(LanguageManager lm, Language lang, ScanningBehavior behavior)
    {
        return lm.get(lang, "secretary_settings", "behavior_" + behavior.name().toLowerCase());
    }

    /**
     * Retrieves the description of a given scanning behavior for a specific language
     * using the provided LanguageManager.
     *
     * @param lm the LanguageManager instance used to fetch localized strings
     * @param lang the language in which the description should be retrieved
     * @param behavior the scanning behavior for which the description is required
     * @return the localized description of the specified scanning behavior
     */
    private static String behaviorDescription(LanguageManager lm, Language lang, ScanningBehavior behavior)
    {
        return lm.get(lang, "secretary_settings",
                "behavior_" + behavior.name().toLowerCase() + "_description");
    }

    /**
     * Sets the scanning behavior for a specific user in the system.
     *
     * @param context the handler context providing access to managers and configurations
     * @param userId the unique identifier of the user for whom the behavior is being set
     * @param behavior the {@code ScanningBehavior} instance defining the desired behavior for the user
     */
    private static void setBehavior(HandlerContext context, long userId, ScanningBehavior behavior)
    {
        try
        {
            context.managers().secretaryConfigurations().setBehavior(userId, behavior);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to save secretary behavior for user {}: {}", userId, e.getMessage());
        }
    }
}