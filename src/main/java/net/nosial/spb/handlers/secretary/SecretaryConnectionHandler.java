package net.nosial.spb.handlers.secretary;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.business.BusinessBotRights;
import org.telegram.telegrambots.meta.api.objects.business.BusinessConnection;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks Telegram Business connections and routes business messages for secretary mode.
 *
 * <p>When a user connects or disconnects the bot as their manager, Telegram delivers a
 * {@code business_connection} update. Connecting inserts a default {@code secretary_configuration}
 * row for the user and replies in the user's private chat with an explanation of how secretary
 * mode works; disconnecting removes the row and confirms the mode has been switched off.
 *
 * <p>Secretary mode requires {@code can_delete_all_messages}; Telegram always provides read access.
 * On connect the handler compares the granted {@code rights} against these requirements and lists
 * any missing permission.
 *
 * <p>Business messages ({@code business_message}, {@code edited_business_message},
 * {@code deleted_business_messages}) are routed through {@link #handleBusinessMessage(HandlerContext)},
 * which resolves the owning user from the message's {@code business_connection_id} and the stored
 * configuration.
 *
 * <p>A sender is tracked per business connection. The first message is analyzed through Federation.
 * In Passive mode the contact is automatically allowed and their message kept, and the owner is
 * notified of every decision. In Strict mode the contact is automatically allowed or denied (denied
 * messages are deleted) and the owner is notified of either decision. Every notification carries
 * Allow and Deny controls so the owner can change the state. Flagged content is echoed to the
 * owner as a standalone message that the notification replies to, since business messages cannot
 * be forwarded by the bot: text is quoted, while attachments are re-served by their file id or
 * described by their details. Later denied messages are silently deleted, and allowed contacts
 * are ignored. Approval choices are isolated by business connection.
 */
@UpdateHandler(UpdateType.BUSINESS_CONNECTION)
public final class SecretaryConnectionHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretaryConnectionHandler.class);
    private static final String CONTACT_CLAIM_CACHE_PREFIX = "secretary-contact-claim:";

    /**
     * Processes a business connection being enabled, disabled, or re-permissioned.
     *
     * @param context the per-update context
     */
    @Override
    public void handle(HandlerContext context)
    {
        BusinessConnection connection = context.update().getBusinessConnection();
        User user = connection.getUser();
        if (user == null)
        {
            LOGGER.warn("Business connection update {} without a user was ignored", context.update().getUpdateId());
            return;
        }

        long userId = user.getId();
        SecretaryMessageHandler.trackBusinessOwner(context, user);
        Language lang = context.managers().languagePreferences()
                .getUserLanguage(userId);

        if (connection.getIsEnabled())
        {
            applyEnable(context, userId);
            sendSecretaryMessage(context, user, enabledHtml(context.languages(), lang, missingPermissions(connection)), secretarySettingsMarkup(context.languages(), lang));
        }
        else
        {
            applyDisable(context, userId, connection.getId());
            sendSecretaryMessage(context, user, disabledHtml(context.languages(), lang), null);
        }
    }

    /**
     * Applies the connect side effect: inserts the default secretary configuration when the user
     * does not yet have one, and (re)binds the configuration to the current business connection id
     * so incoming business messages can be routed back to this user.
     *
     * @param context the update services and configuration
     * @param userId the Telegram user id of the business account
     */
    static void applyEnable(HandlerContext context, long userId)
    {
        try
        {
            context.managers().secretaryConfigurations().setBusinessConnectionId(userId, context.update().getBusinessConnection().getId());
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to store secretary configuration for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Applies the disconnect side effect: removes all secretary data for the user. This deletes the
     * secretary configuration and every tracked contact of the user's business connection, along
     * with the in-memory contact claims for that connection. Global data such as the users table
     * is left untouched.
     *
     * <p>A claim marks a contact's first message as already being analyzed. Left behind after the
     * contacts are deleted, it would make every message from those contacts be skipped unanalyzed
     * if the owner reconnects before the claim expires.
     *
     * <p>Contacts are only ever deleted for one connection: the stored one, or failing that the one
     * the update is about. There is no "delete everything" fallback — a repeated disable update,
     * arriving after the configuration is already gone, would otherwise wipe every user's lists.
     *
     * @param context the update services and configuration
     * @param userId the Telegram user id of the business account
     * @param updateConnectionId the business connection id carried by the update, used when none is stored
     */
    static void applyDisable(HandlerContext context, long userId, String updateConnectionId)
    {
        String connectionId = null;
        try
        {
            if (context.managers().secretaryConfigurations().secretaryConfigurationExists(userId))
            {
                connectionId = context.managers().secretaryConfigurations()
                        .getSecretaryConfiguration(userId)
                        .map(SecretaryConfiguration::businessConnectionId)
                        .orElse(null);
                context.managers().secretaryConfigurations().deleteSecretaryConfiguration(userId);
            }
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to remove secretary configuration for user {}: {}", userId, e.getMessage());
        }

        if (connectionId == null || connectionId.isBlank())
        {
            connectionId = updateConnectionId;
        }
        if (connectionId == null || connectionId.isBlank())
        {
            LOGGER.warn("Disable update for user {} carries no business connection id; contacts left untouched", userId);
            return;
        }

        try
        {
            context.managers().secretaryContacts().deleteSecretaryContacts(connectionId);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to remove secretary contacts for user {}: {}", userId, e.getMessage());
        }

        if (context.cache() != null)
        {
            String claimPrefix = CONTACT_CLAIM_CACHE_PREFIX + connectionId + ':';
            context.cache().removeIf(key -> key.startsWith(claimPrefix));
        }
    }

    /**
     * Returns the required permissions the connected business account has not granted. Telegram
     * always grants secretary read access, so only deleting is checked.
     *
     * @param connection the business connection update
     * @return the missing permission keys, empty when all required permissions are granted
     */
    static List<String> missingPermissions(BusinessConnection connection)
    {
        BusinessBotRights rights = connection.getRights();
        List<String> missing = new ArrayList<>();
        if (rights == null || !Boolean.TRUE.equals(rights.getCanDeleteAllMessages()))
        {
            missing.add("permission_can_delete_all_messages");
        }
        return missing;
    }

    /**
     * Builds the HTML message shown when secretary mode is enabled. When one or more required
     * permissions are missing, the missing permissions are listed before the explanation.
     *
     * @param languageManager the language manager
     * @param lang the user's language
     * @param missing the translation keys of the missing permissions
     * @return the HTML message
     */
    static String enabledHtml(LanguageManager languageManager, Language lang, List<String> missing)
    {
        StringBuilder html = new StringBuilder(
                languageManager.get(lang, "secretary", "enabled_title"));
        if (!missing.isEmpty())
        {
            html.append(languageManager.get(lang, "secretary", "missing_permissions_title"));
            html.append(languageManager.get(lang, "secretary", "missing_permissions_body")).append('\n');
            for (String key : missing)
            {
                html.append(languageManager.get(lang, "secretary", "permission_item", languageManager.get(lang, "secretary", key)));
            }
            html.append('\n').append(languageManager.get(lang, "secretary", "missing_permissions_hint")).append("\n\n");
        }
        html.append(languageManager.get(lang, "secretary", "enabled_body"));
        return html.toString();
    }

    /**
     * Builds the HTML message shown when secretary mode is disabled.
     *
     * @param languageManager the language manager
     * @param lang the user's language
     * @return the HTML message
     */
    static String disabledHtml(LanguageManager languageManager, Language lang)
    {
        return languageManager.get(lang, "secretary", "disabled_title")
                + languageManager.get(lang, "secretary", "disabled_body");
    }

    /**
     * Generates an inline keyboard markup for the secretary settings button.
     *
     * @param languageManager the language manager used to fetch localized text
     * @param lang the language object representing the user's selected language
     * @return an inline keyboard markup containing the secretary settings button
     */
    static InlineKeyboardMarkup secretarySettingsMarkup(LanguageManager languageManager, Language lang)
    {
        return singleRowMarkup(button(languageManager.get(lang, "secretary", "settings_button"), "secset:open"));
    }

    /**
     * Sends a message to the specified user's chat with the provided HTML content and inline keyboard markup
     * using the Telegram client from the provided handler context.
     *
     * @param context the handler context that holds the Telegram client and other update-related information
     * @param user the user to whom the message is sent; must not be null and must have a valid Telegram user ID
     * @param html the HTML content of the message to be sent
     * @param markup the inline keyboard markup to be attached to the message
     */
    private static void sendSecretaryMessage(HandlerContext context, User user, String html, InlineKeyboardMarkup markup)
    {
        if (context.telegramClient() == null || user == null || user.getId() == null)
        {
            return;
        }

        tryExecute(context, "secretary-connection", SendMessage.builder()
                .chatId(String.valueOf(user.getId()))
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());
    }
}
