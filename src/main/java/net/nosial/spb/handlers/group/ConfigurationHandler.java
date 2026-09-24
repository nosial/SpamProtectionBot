package net.nosial.spb.handlers.group;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.managers.ChatConfigurationManager;
import net.nosial.spb.enums.ConfigurationPage;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.enums.JoinProtectionBehavior;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.ChatInfo;
import net.nosial.spb.objects.context.ConfigurationContext;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles inline keyboard callbacks for the configuration menu opened from the {@code /start}
 * command.
 *
 * <p>Every callback carries the configuration session hash and an action (for example
 * {@code enable}, {@code configure}, or {@code set_scanning:MODERATE}). The handler validates
 * that the callback originated from the session owner, applies the action to the chat
 * configuration, and edits the message to show the resulting page.
 *
 * <p>All page rendering, navigation, and state changes for the configuration inline menu live in
 * this class so that future settings pages can be added here without touching other handlers.
 */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = ConfigurationHandler.CALLBACK_PREFIX + ":")
public final class ConfigurationHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationHandler.class);
    // Package-private: the class-level @UpdateHandler annotation reads it.
    static final String CALLBACK_PREFIX = "cfg";

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        // Get the callback query
        CallbackQuery callbackQuery = context.update().getCallbackQuery();
        String data = callbackQuery.getData();
        if (data == null)
        {
            answer(context, callbackQuery);
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
            answerAlert(context, callbackQuery, context.languages().get(resolveLanguage(context, callbackQuery), "configuration", "session_expired"));
            editToExpired(context, callbackQuery);
            return;
        }

        Message message = requireMessage(callbackQuery);
        if (message == null)
        {
            answer(context, callbackQuery);
            return;
        }
        // Checked live on every change rather than once when the menu opened, so an administrator
        // who loses the permission cannot keep using a menu they still have on screen.
        if (requiresChatOwner(action))
        {
            if (!isChatOwner(context, session))
            {
                answerAlert(context, callbackQuery, context.languages().get(sessionLanguage(context, session.userId()), "configuration", "owner_only_alert"));
                return;
            }
        }
        else if (!isNavigationAction(action) && !canChangeInfo(context, session))
        {
            answerAlert(context, callbackQuery, context.languages().get(sessionLanguage(context, session.userId()), "configuration", "change_info_only_alert"));
            return;
        }

        ConfigurationPage nextPage = applyAction(context, session, action);
        session = context.sessions().configuration().updatePage(hash, nextPage);
        if (session == null)
        {
            answerAlert(context, callbackQuery, context.languages().get(resolveLanguage(context, callbackQuery), "configuration", "session_expired"));
            editToExpired(context, callbackQuery);
            return;
        }

        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(session.chatId());
        editToPage(context, message, session, configuration, nextPage);
        String toast = actionToast(context, session, action);
        if (toast == null)
        {
            answer(context, callbackQuery);
        }
        else
        {
            answer(context, callbackQuery, toast);
        }
    }

    /**
     * Sends the main configuration menu as an HTML message.
     *
     * <p>When the session is ephemeral, the message is sent to the group chat as an ephemeral
     * message visible only to the session owner. Otherwise the message is sent to the user's
     * private chat.
     *
     * @param context the per-update command context
     * @param session the validated configuration session
     * @throws TelegramApiException if the menu cannot be sent
     */
    // Entry point: /start and /settings in a group hand control here to draw the menu.
    public static void openMainMenu(HandlerContext context, ConfigurationContext session) throws TelegramApiException
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(session.chatId());
        Language lang = sessionLanguage(context, session.userId());
        String html = buildMainMenuHtml(context, configuration, lang);
        InlineKeyboardMarkup markup = buildMainMenuMarkup(context, configuration, session, lang);

        String chatId = session.ephemeral() ? String.valueOf(session.chatId()) : String.valueOf(session.userId());
        Message source = context.update().getMessage();
        Integer topicId = source != null && source.getChatId() == session.chatId() ? source.getMessageThreadId() : null;
        Message sent = execute(context, "open-main-menu", SendMessage.builder()
                .chatId(chatId)
                .receiverUserId(session.ephemeral() ? session.userId() : null)
                .messageThreadId(topicId)
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());

        if (session.ephemeral())
        {
            context.sessions().configuration().updateEphemeralMessageId(session.hash(), sent.getEphemeralMessageId());
        }
        else
        {
            context.sessions().configuration().updateMessageId(session.hash(), sent.getMessageId());
        }
    }

    /**
     * Updates the configuration menu to show that a notification chat has been linked.
     *
     * <p>This is called from {@link ChannelConnectHandler} after a {@code /connect <id>}
     * command succeeds. When the session is ephemeral, the edit uses ephemeral message editing.
     *
     * @param context the per-update command context
     * @param session the configuration session whose menu should be updated
     * @throws TelegramApiException if the edit cannot be sent
     */
    public static void notifyChannelLinked(HandlerContext context, ConfigurationContext session) throws TelegramApiException
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(session.chatId());
        Language lang = sessionLanguage(context, session.userId());
        InlineKeyboardMarkup markup = buildChannelMarkup(context, configuration, session, lang);
        String html = buildChannelHtml(context, configuration, lang);

        if (session.ephemeral() && session.ephemeralMessageId() != null)
        {
            execute(context, "notify-channel-linked", EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(session.chatId()))
                    .receiverUserId(session.userId())
                    .ephemeralMessageId(session.ephemeralMessageId())
                    .text(html)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(markup)
                    .build());
            return;
        }

        execute(context, "notify-channel-linked", EditMessageText.builder()
                .chatId(String.valueOf(session.userId()))
                .messageId(session.messageId())
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());
    }

    /**
     * Applies the requested action to the chat configuration and returns the page that should be
     * displayed next.
     *
     * @param context the per-update command context
     * @param session the validated configuration session
     * @param action the action encoded in the callback data
     * @return the page to display after the action is applied
     */
    private static ConfigurationPage applyAction(HandlerContext context, ConfigurationContext session, String action)
    {
        long chatId = session.chatId();
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(chatId);
        ConfigurationPage page = session.page();

        switch (action)
        {
            case "enable" ->
            {
                updateConfiguration(context, chatId, manager -> manager.enableChatConfiguration(chatId));
                page = ConfigurationPage.MAIN;
            }
            case "disable" ->
            {
                updateConfiguration(context, chatId, manager -> manager.deleteChatConfiguration(chatId));
                page = ConfigurationPage.MAIN;
            }
            case "configure" -> page = ConfigurationPage.SCANNING;
            case "language" -> page = ConfigurationPage.LANGUAGE;
            case "prev" -> page = adjacentVisiblePage(context, page, false);
            case "next" -> page = adjacentVisiblePage(context, page, true);
            case "menu" -> page = ConfigurationPage.MAIN;
            case "toggle_scanning" ->
            {
                updateConfiguration(context, chatId,
                        manager -> manager.setScanningEnabled(chatId, !configuration.scanningEnabled()));
                page = ConfigurationPage.SCANNING;
            }
            case "toggle_join_protection" ->
            {
                updateConfiguration(context, chatId,
                        manager -> manager.setJoinProtectionEnabled(chatId, !configuration.joinProtectionEnabled()));
                page = ConfigurationPage.JOIN_PROTECTION;
            }
            case "toggle_join_protection_notifications" ->
            {
                updateConfiguration(context, chatId, manager -> manager.setJoinProtectionNotificationsEnabled(
                        chatId, !configuration.joinProtectionNotificationsEnabled()));
                joinProtectionPassiveFallback(context, chatId);
                page = ConfigurationPage.JOIN_PROTECTION;
            }
            case "toggle_privacy" ->
            {
                updateConfiguration(context, chatId,
                        manager -> manager.setPrivacyMode(chatId, !configuration.privacyMode()));
                page = ConfigurationPage.PRIVACY;
            }
            case "toggle_reporting" ->
            {
                if (context.federation().isAuthenticated())
                {
                    updateConfiguration(context, chatId,
                            manager -> manager.setReportingEnabled(chatId, !configuration.reportingEnabled()));
                    page = ConfigurationPage.REPORTING;
                }
                else
                {
                    // The button that reaches this is only ever rendered while authenticated; a
                    // stale one surviving a restart lands back on the main menu instead of a page
                    // that is not supposed to be reachable.
                    page = ConfigurationPage.MAIN;
                }
            }
            case "toggle_scanning_notifications" ->
            {
                boolean enabled = !configuration.scanningNotificationsEnabled();
                updateConfiguration(context, chatId, manager ->
                {
                    manager.setScanningNotificationsEnabled(chatId, enabled);
                    if (!enabled && configuration.scanningBehavior() == ScanningBehavior.PASSIVE)
                    {
                        manager.setScanningBehavior(chatId, ScanningBehavior.MODERATE);
                    }
                });
                page = ConfigurationPage.SCANNING;
            }
            case "toggle_reporting_notifications" ->
            {
                if (context.federation().isAuthenticated())
                {
                    updateConfiguration(context, chatId, manager -> manager.setReportingNotificationsEnabled(
                            chatId, !configuration.reportingNotificationsEnabled()));
                    page = ConfigurationPage.REPORTING;
                }
                else
                {
                    page = ConfigurationPage.MAIN;
                }
            }
            case "disable_channel" ->
            {
                updateConfiguration(context, chatId, manager -> manager.unlinkChannel(chatId));
                joinProtectionPassiveFallback(context, chatId);
                scanningPassiveFallback(context, chatId);
                page = ConfigurationPage.CHANNEL;
            }
            case "toggle_moderator_notifications" ->
            {
                updateConfiguration(context, chatId, manager -> manager.setModeratorNotificationsEnabled(
                        chatId, !configuration.moderatorNotificationsEnabled()));
                joinProtectionPassiveFallback(context, chatId);
                scanningPassiveFallback(context, chatId);
                page = ConfigurationPage.MODERATOR_NOTIFICATIONS;
            }
            default ->
            {
                if (action.startsWith("set_scanning:"))
                {
                    ScanningBehavior requested = ScanningBehavior.fromString(action.substring("set_scanning:".length()));
                    ScanningBehavior behavior = requested == ScanningBehavior.PASSIVE
                            && (!notificationAvenueAvailable(configuration)
                            || !configuration.scanningNotificationsEnabled())
                            ? ScanningBehavior.MODERATE : requested;
                    updateConfiguration(context, chatId, manager -> manager.setScanningBehavior(chatId, behavior));
                    page = ConfigurationPage.SCANNING;
                }
                else if (action.startsWith("set_join_protection:"))
                {
                    JoinProtectionBehavior requested = JoinProtectionBehavior.fromString(
                            action.substring("set_join_protection:".length()));
                    JoinProtectionBehavior behavior = requested == JoinProtectionBehavior.PASSIVE
                            && (!notificationAvenueAvailable(configuration)
                            || !configuration.joinProtectionNotificationsEnabled())
                            ? JoinProtectionBehavior.MODERATE : requested;
                    updateConfiguration(context, chatId, manager -> manager.setJoinProtectionBehavior(chatId, behavior));
                    page = ConfigurationPage.JOIN_PROTECTION;
                }
                else if (action.startsWith("set_language:"))
                {
                    // A button from an older menu may name a language that is no longer shipped.
                    Language language = context.languages().resolve(action.substring("set_language:".length()));
                    if (language != null)
                    {
                        saveChatLanguage(context, chatId, language);
                    }
                    page = ConfigurationPage.LANGUAGE;
                }
                else
                {
                    LOGGER.warn("Unknown configuration action '{}'", action);
                }
            }
        }

        if (session.page() == ConfigurationPage.CHANNEL && page != ConfigurationPage.CHANNEL)
        {
            updateConfiguration(context, chatId, manager -> manager.setChannelLinkVerificationCode(chatId, null));
        }

        return page;
    }

    /**
     * Steps to the next or previous page in carousel order, skipping any page that is not
     * currently reachable rather than landing on it.
     *
     * @param context the per-update command context
     * @param from the current page
     * @param forward {@code true} to move to the next page, {@code false} for the previous one
     * @return the nearest visible page in the requested direction
     */
    private static ConfigurationPage adjacentVisiblePage(HandlerContext context, ConfigurationPage from, boolean forward)
    {
        ConfigurationPage page = forward ? from.next() : from.previous();
        for (int guard = 0; guard < ConfigurationPage.values().length && !isPageVisible(context, page); guard++)
        {
            page = forward ? page.next() : page.previous();
        }
        return page;
    }

    /**
     * Returns whether a settings page makes sense to show right now, rather than always existing
     * but explaining why it will not work. Reporting is the one page whose entire feature — the
     * bot submitting a report under its own identity — the Federation server refuses outright
     * without the bot's own client permissions, so there is nothing to configure until then.
     *
     * @param context the per-update command context
     * @param page the page to test
     * @return {@code true} when the page should be reachable
     */
    private static boolean isPageVisible(HandlerContext context, ConfigurationPage page)
    {
        return page != ConfigurationPage.REPORTING || context.federation().isAuthenticated();
    }

    /**
     * Edits a message in the chat to display the specified configuration page.
     *
     * @param context The handler context containing necessary update and session details for processing.
     * @param message The message object to be edited.
     * @param session The user's configuration session context.
     * @param configuration The current chat configuration that needs to be displayed or updated.
     * @param page The configuration page to display in the chat message.
     * @throws TelegramApiException If editing the message fails due to Telegram API errors.
     */
    private static void editToPage(HandlerContext context, Message message, ConfigurationContext session, ChatConfiguration configuration, ConfigurationPage page) throws TelegramApiException
    {
        Language lang = sessionLanguage(context, session.userId());
        if (page == ConfigurationPage.CHANNEL)
        {
            configuration = refreshChannelLinkVerificationCode(context, configuration);
            context.sessions().configuration().updateChannelLinkVerificationCode(session.hash(), configuration.channelLinkVerificationCode());
        }

        String html = buildPageHtml(context, configuration, page, lang);
        InlineKeyboardMarkup markup = buildPageMarkup(context, configuration, session, page, lang);

        CallbackQuery callbackQuery = context.update().getCallbackQuery();
        try
        {
            editMessage(context, message, callbackQuery, session, html, markup);
        }
        catch (TelegramApiException e)
        {
            if (isUnchangedMessage(e.getMessage()))
            {
                LOGGER.debug("Configuration message {} in chat {} was already current", message.getMessageId(), message.getChatId());
                return;
            }
            MessageHelper.logFailed(context, "edit-to-page", html, e);
            throw e;
        }
    }

    /**
     * Checks if the given message indicates that it is unchanged.
     *
     * @param message the message to check; can be null
     * @return {@code true} if the message is not null and contains the text "message is not modified", otherwise {@code false}
     */
    static boolean isUnchangedMessage(String message)
    {
        return message != null && message.contains("message is not modified");
    }

    /**
     * A write to one chat's configuration, applied through its manager.
     */
    @FunctionalInterface
    private interface ConfigurationWrite
    {
        void apply(ChatConfigurationManager manager) throws DatabaseException;
    }

    /**
     * Updates the configuration for a specified chat by applying the provided
     * configuration changes.
     *
     * @param context the context of the handler containing necessary managers and resources
     * @param chatId the unique identifier of the chat for which the configuration is being updated
     * @param write the configuration write operation to be applied
     */
    private static void updateConfiguration(HandlerContext context, long chatId, ConfigurationWrite write)
    {
        try
        {
            write.apply(context.managers().chatConfigurations());
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to update chat configuration for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Adjusts the join protection behavior of a chat to fallback to a moderate level if the current
     * configuration is passive and certain conditions are not met. This ensures that the join
     * protection mechanism is not left in a less secure state without appropriate notifications.
     *
     * @param context The handler context providing access to the required managers and configurations.
     * @param chatId  The unique identifier of the chat whose join protection behavior is being evaluated and updated.
     */
    private static void joinProtectionPassiveFallback(HandlerContext context, long chatId)
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(chatId);
        if (configuration.joinProtectionBehavior() == JoinProtectionBehavior.PASSIVE && !(notificationAvenueAvailable(configuration) && configuration.joinProtectionNotificationsEnabled()))
        {
            updateConfiguration(context, chatId, manager -> manager.setJoinProtectionBehavior(chatId, JoinProtectionBehavior.MODERATE));
        }
    }

    /**
     * Performs a fallback adjustment for the scanning behavior in case the current
     * configuration has a passive scanning mode and the necessary notification
     * conditions are not met. If the conditions are not satisfied, the scanning
     * behavior is updated to a moderate level for the specified chat.
     *
     * @param context the handler context containing the necessary managers and configurations
     * @param chatId the identifier of the chat whose scanning behavior may need adjustment
     */
    private static void scanningPassiveFallback(HandlerContext context, long chatId)
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(chatId);
        if (configuration.scanningBehavior() == ScanningBehavior.PASSIVE && !(notificationAvenueAvailable(configuration) && configuration.scanningNotificationsEnabled()))
        {
            updateConfiguration(context, chatId, manager -> manager.setScanningBehavior(chatId, ScanningBehavior.MODERATE));
        }
    }

    /**
     * Determines whether the given action requires chat owner privileges.
     *
     * @param action the action to be evaluated
     * @return true if the action requires chat owner privileges, false otherwise
     */
    private static boolean requiresChatOwner(String action)
    {
        return "enable".equals(action) || "disable".equals(action);
    }

    /**
     * Returns the visual feedback toast for a completed configuration action, or {@code null}
     * when the action is pure navigation that needs no confirmation.
     *
     * @param context the per-update command context
     * @param session the validated configuration session
     * @param action the action encoded in the callback data
     * @return the toast text, or {@code null} for navigation actions
     */
    private static String actionToast(HandlerContext context, ConfigurationContext session, String action)
    {
        if (isNavigationAction(action))
        {
            return null;
        }
        Language lang = sessionLanguage(context, session.userId());
        LanguageManager lm = context.languages();
        if ("enable".equals(action))
        {
            return lm.get(lang, "configuration", "toast_enabled");
        }
        if ("disable".equals(action))
        {
            return lm.get(lang, "configuration", "toast_disabled");
        }
        if (action.startsWith("set_language:"))
        {
            return lm.get(lang, "configuration", "toast_language_changed");
        }
        return lm.get(lang, "configuration", "toast_settings_updated");
    }

    /**
     * Determines whether the provided action is a valid navigation action.
     *
     * @param action the action to check; expected values are "prev", "next", "menu", "configure", or "language"
     * @return true if the action is one of the valid navigation actions, false otherwise
     */
    private static boolean isNavigationAction(String action)
    {
        return "prev".equals(action) || "next".equals(action) || "menu".equals(action) || "configure".equals(action) || "language".equals(action);
    }

    /**
     * Determines if the user is the owner of a chat by checking the user's membership status.
     *
     * @param context The HandlerContext object containing application-specific details
     *                and logic to interact with the Telegram client.
     * @param session The ConfigurationContext object containing the chat ID and user ID
     *                required to evaluate the user's ownership in a specific chat.
     * @return {@code true} if the user is the owner of the chat; {@code false} otherwise
     *         or if the ownership verification fails due to an exception.
     */
    private static boolean isChatOwner(HandlerContext context, ConfigurationContext session)
    {
        ChatMember member = fetchMember(context, session);
        return member != null && isChatOwner(member);
    }

    /**
     * Determines if the user may change the chat's settings: the owner, or an administrator with
     * the Change Group Information permission. Checked against Telegram, not the cached
     * administrator list.
     *
     * @param context the per-update command context
     * @param session the configuration session naming the chat and the user
     * @return {@code true} when the user may change settings; {@code false} otherwise or when the
     *         check fails
     */
    private static boolean canChangeInfo(HandlerContext context, ConfigurationContext session)
    {
        return canChangeInfo(fetchMember(context, session));
    }

    /**
     * Determines if the given chat member may change the chat's settings.
     *
     * @param member the chat member, may be {@code null}
     * @return {@code true} for the owner and for administrators who can change the chat's information
     */
    static boolean canChangeInfo(ChatMember member)
    {
        return member instanceof ChatMemberOwner
                || (member instanceof ChatMemberAdministrator administrator
                && Boolean.TRUE.equals(administrator.getCanChangeInfo()));
    }

    /**
     * Fetches the session user's current membership in the session's chat from Telegram.
     *
     * @param context the per-update command context
     * @param session the configuration session naming the chat and the user
     * @return the membership, or {@code null} when it could not be fetched
     */
    private static ChatMember fetchMember(HandlerContext context, ConfigurationContext session)
    {
        try
        {
            return context.telegramClient().execute(GetChatMember.builder()
                    .chatId(session.chatId())
                    .userId(session.userId())
                    .build());
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to verify permissions of {} in chat {}: {}", session.userId(), session.chatId(), e.getMessage());
            return null;
        }
    }

    /**
     * Determines if the given chat member is the owner of the chat.
     *
     * @param member the ChatMember object to be checked
     * @return {@code true} if the member is an instance of ChatMemberOwner, {@code false} otherwise
     */
    static boolean isChatOwner(ChatMember member)
    {
        return member instanceof ChatMemberOwner;
    }

    /**
     * Builds an HTML representation of the main menu for the chat configuration settings.
     * The resulting string contains information about various features, including their statuses
     * and configurations, as well as optional server information if available.
     *
     * @param context The {@code HandlerContext} providing access to language resources,
     *                chat information, and federation details.
     * @param configuration The {@code ChatConfiguration} object containing details about
     *                      the chat's setup, such as enabled features and behaviors.
     * @param lang The {@code Language} object representing the user's language preference
     *             for localizing the menu content.
     * @return A {@code String} containing the localized HTML structure for the main menu,
     *         reflecting the current configuration and state of the chat and server.
     */
    private static String buildMainMenuHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        ChatInfo chatInfo = context.chatInfo().getIfPresent(configuration.chatId());
        String chatName = chatInfo != null ? HtmlEscape.escape(chatInfo.name()) : HtmlEscape.escape(lm.get(lang, "configuration", "this_chat"));
        StringBuilder html = new StringBuilder();
        if (!configuration.enabled())
        {
            return lm.get(lang, "configuration", "main_menu.disabled_header", chatName);
        }

        html.append(lm.get(lang, "configuration", "main_menu.enabled_header", chatName));
        appendPageLine(html, lm.get(lang, "configuration",
                "main_menu." + (configuration.scanningEnabled() ? "scanning_with_level" : "scanning"),
                configuration.scanningEnabled() ? behaviorDisplayName(lm, lang, configuration.scanningBehavior()) : status(lm, lang, false)));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.scanning_notifications", status(lm, lang, configuration.scanningNotificationsEnabled())));
        appendPageLine(html, lm.get(lang, "configuration",
                "main_menu." + (configuration.joinProtectionEnabled() ? "join_protection_with_level" : "join_protection"),
                configuration.joinProtectionEnabled() ? behaviorDisplayName(lm, lang, configuration.joinProtectionBehavior()) : status(lm, lang, false)));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.join_protection_notifications", status(lm, lang, configuration.joinProtectionNotificationsEnabled())));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.reporting", status(lm, lang, configuration.reportingEnabled())));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.report_notifications", status(lm, lang, configuration.reportingNotificationsEnabled())));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.privacy_mode", status(lm, lang, configuration.privacyMode())));
        appendPageLine(html, lm.get(lang, "configuration", "main_menu.moderator_notifications", status(lm, lang, configuration.moderatorNotificationsEnabled())));
        appendPageLine(html, configuration.channelLinkId() != null
                ? lm.get(lang, "configuration", "main_menu.chat_linking", configuration.channelLinkId())
                : lm.get(lang, "configuration", "main_menu.chat_linking_not_linked"));

        var serverInformation = MessageHelper.serverInformation(context);
        if (serverInformation != null)
        {
            html.append(lm.get(lang, "configuration", "main_menu.federation_server_header"));
            appendPageLine(html, lm.get(lang, "configuration", "main_menu.federation_name", serverInformation.serverName()));
            appendPageLine(html, lm.get(lang, "configuration", "main_menu.federation_host", MessageHelper.serverHost(context)));
            appendPageLine(html, lm.get(lang, "configuration", "main_menu.federation_api_version", serverInformation.apiVersion()));
            appendPageLine(html, lm.get(lang, "configuration", "main_menu.federation_access",
                    lm.get(lang, "configuration", context.federation().isAuthenticated()
                            ? "main_menu.federation_access_authenticated"
                            : "main_menu.federation_access_anonymous")));
        }

        return html.toString();
    }

    /**
     * Builds the main menu markup for an inline keyboard based on the current chat configuration
     * and language settings provided in the session and context.
     *
     * @param context The handler context, which provides access to utilities such as the language manager.
     * @param configuration The chat-specific configuration that determines the bot's current state.
     * @param session The configuration context for the active session, used to manage interaction metadata.
     * @param lang The language identifier used to fetch localized strings for button labels.
     * @return An instance of {@code InlineKeyboardMarkup} representing the main menu keyboard layout.
     */
    private static InlineKeyboardMarkup buildMainMenuMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.enabled(),
                "enable", lm.get(lang, "buttons", "enable_bot"),
                "disable", lm.get(lang, "buttons", "disable_bot"))));
        if (configuration.enabled())
        {
            rows.add(new InlineKeyboardRow(
                    actionButton(session, "configure",
                            lm.get(lang, "buttons", "configure_settings")),
                    actionButton(session, "language",
                            lm.get(lang, "buttons", "set_language"))));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds the HTML content for a specific configuration page.
     *
     * @param context the handler context that provides the required execution context and state
     * @param configuration the chat configuration containing settings and preferences
     * @param page the specific configuration page to generate content for
     * @param lang the language to be used for generating the content
     * @return the HTML content as a string for the given configuration page
     */
    private static String buildPageHtml(HandlerContext context, ChatConfiguration configuration, ConfigurationPage page, Language lang)
    {
        return switch (page)
        {
            case MAIN -> buildMainMenuHtml(context, configuration, lang);
            case SCANNING -> buildScanningHtml(context, configuration, lang);
            case JOIN_PROTECTION -> buildJoinProtectionHtml(context, configuration, lang);
            case PRIVACY -> buildPrivacyHtml(context, configuration, lang);
            case REPORTING -> buildReportingHtml(context, configuration, lang);
            case MODERATOR_NOTIFICATIONS -> buildModeratorNotificationsHtml(context, configuration, lang);
            case CHANNEL -> buildChannelHtml(context, configuration, lang);
            case LANGUAGE -> buildLanguageHtml(context, configuration, lang);
        };
    }

    /**
     * Builds an inline keyboard markup for the specified configuration page.
     *
     * @param context the handler context containing necessary details for processing
     * @param configuration the chat configuration associated with the current operation
     * @param session the session-specific configuration context
     * @param page the configuration page for which the markup is to be built
     * @param lang the language context used for localization
     * @return an instance of InlineKeyboardMarkup representing the desired page layout
     */
    private static InlineKeyboardMarkup buildPageMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, ConfigurationPage page, Language lang)
    {
        return switch (page)
        {
            case MAIN -> buildMainMenuMarkup(context, configuration, session, lang);
            case SCANNING -> buildScanningMarkup(context, configuration, session, lang);
            case JOIN_PROTECTION -> buildJoinProtectionMarkup(context, configuration, session, lang);
            case PRIVACY -> buildPrivacyMarkup(context, configuration, session, lang);
            case REPORTING -> buildReportingMarkup(context, configuration, session, lang);
            case MODERATOR_NOTIFICATIONS -> buildModeratorNotificationsMarkup(context, configuration, session, lang);
            case CHANNEL -> buildChannelMarkup(context, configuration, session, lang);
            case LANGUAGE -> buildLanguagePageMarkup(context, session, lang);
        };
    }

    /**
     * Builds and returns a localized HTML representation of scanning configuration details.
     *
     * @param context the handler context providing access to required utilities and resources
     * @param configuration the chat configuration object containing scanning settings
     * @param lang the language for localization of the HTML content
     * @return a string containing the localized HTML content describing the scanning configuration
     */
    private static String buildScanningHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "configuration", "scanning.title"));
        html.append(lm.get(lang, "configuration", "scanning.description"));
        if (configuration.privacyMode())
        {
            appendPrivacyModeNote(html, lm, lang, lm.get(lang, "configuration", "scanning.privacy_mode_note"));
        }
        html.append(lm.get(lang, "configuration", "scanning.status", status(lm, lang, configuration.scanningEnabled()))).append("\n\n");
        if (configuration.scanningEnabled())
        {
            html.append(lm.get(lang, "configuration", "scanning.level", scanningLevel(lm, lang, configuration.scanningBehavior()))).append("\n\n");
            html.append(notificationAvenueAvailable(configuration)
                    ? lm.get(lang, "configuration", "scanning.notifications",
                    status(lm, lang, configuration.scanningNotificationsEnabled()))
                    : lm.get(lang, "configuration", "scanning.notifications_unavailable")).append("\n\n");
        }
        return html.toString();
    }

    /**
     * Builds an InlineKeyboardMarkup object for scanning configuration settings.
     * It generates an interactive markup with buttons based on the given settings
     * and user preferences, such as enabling/disabling scanning, configuring scanning
     * behavior, and enabling/disabling scanning notifications.
     *
     * @param context The handler context providing access to resources such as language management.
     * @param configuration The chat configuration containing scanning and notification settings.
     * @param session The configuration context for the current session containing state-specific data.
     * @param lang The language used for localized text labels on the buttons.
     * @return An InlineKeyboardMarkup object containing the dynamically constructed interactive keyboard layout.
     */
    private static InlineKeyboardMarkup buildScanningMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.scanningEnabled(),
                "toggle_scanning", lm.get(lang, "buttons", "enable"),
                "toggle_scanning", lm.get(lang, "buttons", "disable"))));
        if (configuration.scanningEnabled())
        {
            List<InlineKeyboardButton> behaviorButtons = new ArrayList<>();
            for (ScanningBehavior behavior : ScanningBehavior.values())
            {
                if (behavior == configuration.scanningBehavior())
                {
                    continue;
                }
                if (behavior == ScanningBehavior.PASSIVE && (!configuration.scanningNotificationsEnabled() || !notificationAvenueAvailable(configuration)))
                {
                    continue;
                }
                behaviorButtons.add(actionButton(session, "set_scanning:" + behavior.name(), behaviorDisplayName(lm, lang, behavior)));
            }
            rows.addAll(rowsOfTwo(behaviorButtons));
            if (notificationAvenueAvailable(configuration))
            {
                rows.add(new InlineKeyboardRow(toggleButton(session, configuration.scanningNotificationsEnabled(),
                        "toggle_scanning_notifications", lm.get(lang, "buttons", "enable_notifications"),
                        "toggle_scanning_notifications", lm.get(lang, "buttons", "disable_notifications"))));
            }
        }
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds an HTML representation of the join protection settings for the given chat configuration.
     *
     * @param context The handler context providing access to language management and other utilities.
     * @param configuration The chat configuration containing the join protection settings.
     * @param lang The language to be used for generating the HTML output.
     * @return A string containing the HTML representation of the join protection settings.
     */
    private static String buildJoinProtectionHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "configuration", "join_protection.title"));
        html.append(lm.get(lang, "configuration", "join_protection.description"));
        if (configuration.privacyMode())
        {
            appendPrivacyModeNote(html, lm, lang, lm.get(lang, "configuration", "join_protection.privacy_mode_note"));
        }

        html.append(lm.get(lang, "configuration", "join_protection.status", status(lm, lang, configuration.joinProtectionEnabled()))).append("\n\n");
        if (configuration.joinProtectionEnabled())
        {
            html.append(lm.get(lang, "configuration", "join_protection.level", joinProtectionLevel(lm, lang, configuration.joinProtectionBehavior()))).append("\n\n");
            html.append(notificationAvenueAvailable(configuration)
                    ? lm.get(lang, "configuration", "join_protection.notifications",
                    status(lm, lang, configuration.joinProtectionNotificationsEnabled()))
                    : lm.get(lang, "configuration", "join_protection.notifications_unavailable")).append("\n\n");
        }
        return html.toString();
    }

    /**
     * Builds an inline keyboard markup for join protection settings in a chat.
     *
     * @param context The handler context providing language and other services.
     * @param configuration The chat configuration containing join protection settings.
     * @param session The configuration context for tracking user-specific state.
     * @param lang The language to be used for the button labels and text.
     * @return The constructed {@code InlineKeyboardMarkup} object for join protection settings.
     */
    private static InlineKeyboardMarkup buildJoinProtectionMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.joinProtectionEnabled(),
                "toggle_join_protection", lm.get(lang, "buttons", "enable"),
                "toggle_join_protection", lm.get(lang, "buttons", "disable"))));
        if (configuration.joinProtectionEnabled())
        {
            List<InlineKeyboardButton> behaviorButtons = new ArrayList<>();
            boolean jpNotificationsAvailable = notificationAvenueAvailable(configuration) && configuration.joinProtectionNotificationsEnabled();
            for (JoinProtectionBehavior behavior : JoinProtectionBehavior.values())
            {
                if (behavior == configuration.joinProtectionBehavior())
                {
                    continue;
                }
                if (behavior == JoinProtectionBehavior.PASSIVE && !jpNotificationsAvailable)
                {
                    continue;
                }
                behaviorButtons.add(actionButton(session, "set_join_protection:" + behavior.name(), behaviorDisplayName(lm, lang, behavior)));
            }
            rows.addAll(rowsOfTwo(behaviorButtons));
            if (notificationAvenueAvailable(configuration))
            {
                rows.add(new InlineKeyboardRow(toggleButton(session, configuration.joinProtectionNotificationsEnabled(),
                        "toggle_join_protection_notifications", lm.get(lang, "buttons", "enable_notifications"),
                        "toggle_join_protection_notifications", lm.get(lang, "buttons", "disable_notifications"))));
            }
        }
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds the HTML string representing the privacy information for the given chat configuration.
     *
     * @param context The handler context used to access the language manager.
     * @param configuration The chat configuration containing privacy-related settings.
     * @param lang The language in which the privacy information should be rendered.
     * @return A string containing the generated privacy HTML content in the specified language.
     */
    private static String buildPrivacyHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        return lm.get(lang, "configuration", "privacy.title") +
                lm.get(lang, "configuration", "privacy.description") +
                lm.get(lang, "configuration", "privacy.status", status(lm, lang, configuration.privacyMode())) +
                "\n\n";
    }

    /**
     * Builds an InlineKeyboardMarkup object for privacy settings, providing necessary
     * toggle options and navigation based on the given configuration and session context.
     *
     * @param context the HandlerContext providing utilities such as the LanguageManager
     * @param configuration the ChatConfiguration holding privacy settings and related configurations
     * @param session the ConfigurationContext representing the current user session
     * @param lang the Language object specifying the language for the interface
     * @return an InlineKeyboardMarkup object with buttons for privacy toggling and navigation
     */
    private static InlineKeyboardMarkup buildPrivacyMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.privacyMode(),
                "toggle_privacy", lm.get(lang, "buttons", "enable"),
                "toggle_privacy", lm.get(lang, "buttons", "disable"))));
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds an HTML string containing reporting-related information based on the given context,
     * configuration, and language settings.
     *
     * @param context the handler context providing access to language management and other services
     * @param configuration the chat configuration containing reporting and privacy mode settings
     * @param lang the language in which the reporting information should be localized
     * @return a String containing the localized HTML representation of the reporting information
     */
    private static String buildReportingHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "configuration", "reporting.title"));
        html.append(lm.get(lang, "configuration", "reporting.description"));
        if (configuration.privacyMode())
        {
            appendPrivacyModeNote(html, lm, lang, lm.get(lang, "configuration", "reporting.privacy_mode_note"));
        }
        html.append(lm.get(lang, "configuration", "reporting.status", status(lm, lang, configuration.reportingEnabled()))).append("\n\n");
        if (configuration.reportingEnabled())
        {
            html.append(notificationAvenueAvailable(configuration)
                    ? lm.get(lang, "configuration", "reporting.notifications",
                    status(lm, lang, configuration.reportingNotificationsEnabled()))
                    : lm.get(lang, "configuration", "reporting.notifications_unavailable")).append("\n\n");
        }
        return html.toString();
    }

    /**
     * Builds an InlineKeyboardMarkup that serves as the reporting options menu, providing
     * options to toggle reporting features and navigate using the provided session and language settings.
     *
     * @param context the handler context providing access to supporting utilities such as the language manager
     * @param configuration the chat configuration containing settings such as reportingEnabled and reportingNotificationsEnabled
     * @param session the configuration context for the current session
     * @param lang the language object for determining localized button labels
     * @return an InlineKeyboardMarkup containing rows of interactive buttons for managing reporting features
     */
    private static InlineKeyboardMarkup buildReportingMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.reportingEnabled(),
                "toggle_reporting", lm.get(lang, "buttons", "enable"),
                "toggle_reporting", lm.get(lang, "buttons", "disable"))));
        if (configuration.reportingEnabled() && notificationAvenueAvailable(configuration))
        {
            rows.add(new InlineKeyboardRow(toggleButton(session, configuration.reportingNotificationsEnabled(),
                    "toggle_reporting_notifications", lm.get(lang, "buttons", "enable_notifications"),
                    "toggle_reporting_notifications", lm.get(lang, "buttons", "disable_notifications"))));
        }
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds the HTML content for moderator notifications settings based on the provided context, configuration, and language.
     *
     * @param context the handler context containing necessary data and utilities such as the language manager
     * @param configuration the chat configuration object containing moderator notifications settings
     * @param lang the language to be used for retrieving localized content
     * @return a string representing the HTML content for the moderator notifications settings
     */
    private static String buildModeratorNotificationsHtml(HandlerContext context, ChatConfiguration configuration,
                                                          Language lang)
    {
        LanguageManager lm = context.languages();
        return lm.get(lang, "configuration", "moderator_notifications.title") +
                lm.get(lang, "configuration", "moderator_notifications.description") +
                lm.get(lang, "configuration", "moderator_notifications.status", status(lm, lang, configuration.moderatorNotificationsEnabled())) +
                "\n\n";
    }

    /**
     * Builds an inline keyboard markup for moderator notifications configuration.
     *
     * @param context the handler context containing essential application dependencies
     * @param configuration the configuration specific to the chat
     * @param session the current configuration context for the session
     * @param lang the language preference for generating localized text
     * @return an InlineKeyboardMarkup object representing the moderator notifications markup
     */
    private static InlineKeyboardMarkup buildModeratorNotificationsMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(toggleButton(session, configuration.moderatorNotificationsEnabled(),
                "toggle_moderator_notifications", lm.get(lang, "buttons", "enable"),
                "toggle_moderator_notifications", lm.get(lang, "buttons", "disable"))));
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds an HTML representation of a channel's configuration, including its title,
     * description, and linking status. If the channel is not linked, an instruction
     * for linking the channel is included.
     *
     * @param context the handler context providing access to required services like the language manager
     * @param configuration the chat configuration containing channel details and statuses
     * @param lang the language in which the text should be localized
     * @return a String containing the formatted HTML representation of the channel's information
     */
    private static String buildChannelHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "configuration", "channel.title"));
        html.append(lm.get(lang, "configuration", "channel.description"));

        if (configuration.channelLinkId() != null)
        {
            html.append(lm.get(lang, "configuration", "channel.linked_chat", configuration.channelLinkId()));
        }
        else
        {
            Long verificationCode = configuration.channelLinkVerificationCode();
            html.append(lm.get(lang, "configuration", "channel.status_not_linked"));
            html.append(lm.get(lang, "configuration", "channel.post_command_instruction"));
            html.append("<code>/connect ").append(verificationCode != null ? verificationCode : "N/A").append("</code>\n");
        }

        return html.toString();
    }

    /**
     * Appends a privacy mode note to the provided StringBuilder.
     *
     * @param html the StringBuilder to append the privacy mode note to
     * @param lm the LanguageManager used to retrieve localized labels
     * @param lang the language in which the privacy mode label should be retrieved
     * @param text the additional text to append after the privacy mode label
     */
    private static void appendPrivacyModeNote(StringBuilder html, LanguageManager lm, Language lang, String text)
    {
        html.append(lm.get(lang, "configuration", "privacy_mode_label")).append(text).append("\n\n");
    }

    /**
     * Builds an InlineKeyboardMarkup instance for a chat interface.
     *
     * @param context the handler context providing access to various utilities and resources
     * @param configuration the configuration object containing data about the current chat settings
     * @param session the current session context used for retrieving user-specific data
     * @param lang the language context used for retrieving localized strings
     * @return an InlineKeyboardMarkup object containing the constructed keyboard layout
     */
    private static InlineKeyboardMarkup buildChannelMarkup(HandlerContext context, ChatConfiguration configuration, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (configuration.channelLinkId() != null)
        {
            rows.add(new InlineKeyboardRow(actionButton(session, "disable_channel", lm.get(lang, "buttons", "unlink_chat"))));
        }
        rows.add(navigationRow(lm, lang, session));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Builds an HTML representation of language selection and the current language.
     *
     * @param context the handler context used to access language management utilities
     * @param configuration the chat configuration containing relevant chat settings
     * @param lang the language in which the HTML will be generated
     * @return a formatted string representing the language selection and the currently selected language
     */
    private static String buildLanguageHtml(HandlerContext context, ChatConfiguration configuration, Language lang)
    {
        LanguageManager lm = context.languages();
        Language current = context.managers().languagePreferences().getChatLanguage(configuration.chatId());
        return lm.get(lang, "general", "language.select") + "\n\n" + lm.get(lang, "general", "language.current", current.emoji(), current.name());
    }

    /**
     * Builds the inline keyboard markup for the language selection page, including navigation buttons.
     *
     * @param context the handler context providing access to language management and other utilities
     * @param session the configuration context of the current session
     * @param lang the current language of the user
     * @return an InlineKeyboardMarkup object representing the language selection page's layout
     */
    private static InlineKeyboardMarkup buildLanguagePageMarkup(HandlerContext context, ConfigurationContext session, Language lang)
    {
        LanguageManager lm = context.languages();
        List<InlineKeyboardRow> rows = new ArrayList<>(languageRows(context, session));
        rows.add(new InlineKeyboardRow(actionButton(session, "menu", lm.get(lang, "buttons", "nav_menu"))));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Generates a list of InlineKeyboardRow objects, each representing a row of buttons
     * for selecting a language. Each button corresponds to a language and is displayed
     * with an associated emoji.
     *
     * @param context the handler context containing information about available languages
     * @param session the configuration context used for creating button actions
     * @return a list of InlineKeyboardRow objects representing rows of language selection buttons
     */
    private static List<InlineKeyboardRow> languageRows(HandlerContext context, ConfigurationContext session)
    {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Language language : context.languages().availableLanguages())
        {
            buttons.add(actionButton(session, "set_language:" + language.code(), language.emoji()));
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int index = 0; index < buttons.size(); index += 4)
        {
            rows.add(new InlineKeyboardRow(buttons.subList(index, Math.min(index + 4, buttons.size()))));
        }
        return rows;
    }

    /**
     * Saves the preferred language for a specific chat.
     *
     * @param context the handler context providing access to necessary managers
     * @param chatId the unique identifier of the chat
     * @param language the language to be set as the preferred chat language
     */
    private static void saveChatLanguage(HandlerContext context, long chatId, Language language)
    {
        try
        {
            context.managers().languagePreferences().setChatLanguage(chatId, language);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to save chat language for chat {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Creates an InlineKeyboardRow containing navigation buttons for previous, menu, and next actions.
     *
     * @param lm the LanguageManager instance used to retrieve localized button labels
     * @param lang the Language object representing the user's selected language
     * @param session the ConfigurationContext associated with the current session
     * @return an InlineKeyboardRow containing the navigation buttons
     */
    private static InlineKeyboardRow navigationRow(LanguageManager lm, Language lang, ConfigurationContext session)
    {
        return new InlineKeyboardRow(
                actionButton(session, "prev", lm.get(lang, "buttons", "nav_prev")),
                actionButton(session, "menu", lm.get(lang, "buttons", "nav_menu")),
                actionButton(session, "next", lm.get(lang, "buttons", "nav_next"))
        );
    }

    /**
     * Builds the button that flips a setting, labelled with what pressing it will do.
     *
     * @param session the configuration session the button belongs to
     * @param current whether the setting is currently on
     * @param enableAction the callback action that turns it on
     * @param enableLabel the label shown while it is off
     * @param disableAction the callback action that turns it off
     * @param disableLabel the label shown while it is on
     * @return the inline button
     */
    private static InlineKeyboardButton toggleButton(ConfigurationContext session, boolean current, String enableAction, String enableLabel, String disableAction, String disableLabel)
    {
        return actionButton(session, current ? disableAction : enableAction, current ? disableLabel : enableLabel);
    }

    /**
     * Creates an inline keyboard button with the specified label and callback data based on the session and action.
     *
     * @param session the configuration context representing the current session
     * @param action the action identifier to be included in the callback data
     * @param label the display label for the button
     * @return an InlineKeyboardButton object with the
     */
    private static InlineKeyboardButton actionButton(ConfigurationContext session, String action, String label)
    {
        return button(label, CALLBACK_PREFIX + ":" + session.hash() + ":" + action);
    }

    /**
     * Retrieves the localized status message for the specified language and enabled state.
     *
     * @param lm The LanguageManager instance used to fetch the localized message.
     * @param lang The language for which the status message is to be retrieved.
     * @param enabled The status indicator; true for "enabled" and false for "disabled".
     * @return A localized message indicating the status ("enabled" or "disabled") in the specified language.
     */
    private static String status(LanguageManager lm, Language lang, boolean enabled)
    {
        return lm.get(lang, "general", enabled ? "enabled" : "disabled");
    }

    /**
     * Determines if a notification avenue is available based on the given chat configuration.
     *
     * @param configuration the chat configuration object containing moderator notification and channel link details
     * @return true if either moderator notifications are enabled or a channel link ID is present, false otherwise
     */
    private static boolean notificationAvenueAvailable(ChatConfiguration configuration)
    {
        return configuration.moderatorNotificationsEnabled() || configuration.channelLinkId() != null;
    }

    /**
     * Constructs a string representing the scanning level by combining the display name
     * and description of the specified scanning behavior in the given language context.
     *
     * @param lm the LanguageManager instance used to fetch language-specific data
     * @param lang the language context for generating the display name and description
     * @param behavior the scanning behavior whose details are to be combined into the result
     * @return a string combining the display name and description of the scanning behavior
     */
    private static String scanningLevel(LanguageManager lm, Language lang, ScanningBehavior behavior)
    {
        return behaviorDisplayName(lm, lang, behavior) + " — " + behaviorDescription(lm, lang, behavior);
    }

    /**
     * Joins the display name and the description of the provided join protection behavior
     * into a single formatted string.
     *
     * @param lm the language manager used for obtaining localized strings
     * @param lang the language to be used for localization
     * @param behavior the join protection behavior whose information is being formatted
     * @return a formatted string that combines the display name and the description of the join protection behavior
     */
    private static String joinProtectionLevel(LanguageManager lm, Language lang, JoinProtectionBehavior behavior)
    {
        return behaviorDisplayName(lm, lang, behavior) + " — " + behaviorDescription(lm, lang, behavior);
    }

    /**
     * Retrieves the display name for the given scanning behavior by obtaining the
     * corresponding value from the LanguageManager.
     *
     * @param lm the LanguageManager instance used to fetch localized strings
     * @param lang the language context to determine the appropriate localization
     * @param behavior the scanning behavior whose display name is to be retrieved
     * @return the localized display name for the specified scanning behavior
     */
    private static String behaviorDisplayName(LanguageManager lm, Language lang, ScanningBehavior behavior)
    {
        return lm.get(lang, "configuration", "scanning.behavior_" + behavior.name().toLowerCase());
    }

    /**
     * Retrieves the description for a specific scanning behavior in the specified language.
     *
     * @param lm the LanguageManager instance used to fetch localized descriptions
     * @param lang the language for which the description should be retrieved
     * @param behavior the scanning behavior whose description needs to be fetched
     * @return the localized description of the specified scanning behavior
     */
    private static String behaviorDescription(LanguageManager lm, Language lang, ScanningBehavior behavior)
    {
        return lm.get(lang, "configuration", "scanning.behavior_" + behavior.name().toLowerCase() + "_description");
    }

    /**
     * Retrieves the display name for a specified join protection behavior based on the given language and language manager.
     *
     * @param lm the language manager to retrieve localized strings
     * @param lang the language for which the display name should be retrieved
     * @param behavior the join protection behavior whose display name is to be determined
     * @return a localized display name as a string for the specified join protection behavior
     */
    private static String behaviorDisplayName(LanguageManager lm, Language lang, JoinProtectionBehavior behavior)
    {
        return lm.get(lang, "configuration", "join_protection.behavior_" + behavior.name().toLowerCase());
    }

    /**
     * Retrieves a localized description for the specified join protection behavior.
     *
     * @param lm the language manager used for retrieving localized messages
     * @param lang the language in which the description should be provided
     * @param behavior the join protection behavior for which the description is requested
     * @return the localized description of the specified join protection behavior
     */
    private static String behaviorDescription(LanguageManager lm, Language lang, JoinProtectionBehavior behavior)
    {
        return lm.get(lang, "configuration", "join_protection.behavior_" + behavior.name().toLowerCase() + "_description");
    }

    /**
     * Appends a line of text to the given StringBuilder instance with a newline character at the end.
     *
     * @param html the StringBuilder instance to which the line will be appended
     * @param line the line of text to append to the StringBuilder
     */
    private static void appendPageLine(StringBuilder html, String line)
    {
        html.append(line).append('\n');
    }

    /**
     * Refreshes the channel verification code for the configuration and returns the updated
     * configuration. This is called each time the channel page is rendered while no channel is
     * linked, generating a fresh code and invalidating any previous one.
     *
     * @param context the command context
     * @param configuration the current chat configuration
     * @return the configuration with a newly generated verification code
     */
    public static ChatConfiguration refreshChannelLinkVerificationCode(HandlerContext context, ChatConfiguration configuration)
    {
        if (configuration.channelLinkId() != null)
        {
            return configuration;
        }

        long chatId = configuration.chatId();
        long verificationCode = ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
        updateConfiguration(context, chatId, manager -> manager.setChannelLinkVerificationCode(chatId, verificationCode));
        return context.managers().chatConfigurations().resolve(chatId);
    }
}
