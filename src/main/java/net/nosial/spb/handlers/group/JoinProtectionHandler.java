package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.enums.ModerationAction;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.utilities.EntityActionResolver;
import net.nosial.spb.utilities.MessageHelper;
import net.nosial.spb.utilities.ModerationActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.ChatJoinRequest;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nosial.spb.classes.notifications.NotificationSender;

/**
 * Checks members against Federation as they join, and acts on what it finds.
 *
 * <p>Runs as an observer after {@link ScanningHandler}, so the member it is deciding about has
 * already been registered and the chat's administrator list is current. Joins arrive in three
 * shapes — a service message, a membership update, or a join request — and all three are declared
 * here so the chat is protected, however, Telegram reports the event.
 */
@UpdateHandler(value = {UpdateType.MESSAGE, UpdateType.CHAT_MEMBER, UpdateType.CHAT_JOIN_REQUEST}, mode = DispatchMode.OBSERVE, priority = 900)
public final class JoinProtectionHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(JoinProtectionHandler.class);
    private static final String JOIN_CLAIM_CACHE_PREFIX = "join-protection:";

    /**
     * Processes one Telegram update for new group members.
     *
     * @param context the update services and configuration
     */
    @Override
    public void handle(HandlerContext context)
    {
        Update update = context.update();
        if (update.hasChatMember())
        {
            ChatMemberUpdated membership = update.getChatMember();
            if (isJoiningMember(membership))
            {
                protectJoiningMember(context, membership.getChat(), membership.getNewChatMember().getUser(), true);
            }
            return;
        }

        if (update.hasChatJoinRequest())
        {
            ChatJoinRequest joinRequest = update.getChatJoinRequest();
            protectJoinRequest(context, joinRequest);
            return;
        }

        Message message = update.getMessage();
        if (message == null || message.getNewChatMembers() == null)
        {
            return;
        }
        for (User member : message.getNewChatMembers())
        {
            protectJoiningMember(context, message.getChat(), member, false);
        }
    }

    private static void protectJoiningMember(HandlerContext context, Chat chat, User member,
                                             boolean membershipUpdate)
    {
        if (chat == null || member == null || member.getIsBot() || !isGroup(chat))
        {
            return;
        }

        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(chat.getId());
        FederationService federation = context.federation();
        if (!configuration.enabled() || !configuration.joinProtectionEnabled() || !federation.isAvailable()
                || !claimJoin(context, chat.getId(), member.getId()))
        {
            return;
        }

        if (membershipUpdate && !configuration.privacyMode())
        {
            RegistrationHandler.pushUser(federation, member);
        }

        EntityQueryResult query;
        try
        {
            query = federation.queryEntity(member.getId() + "@telegram.org").orElse(null);
        }
        catch (FederationException e)
        {
            context.cache().remove(joinCacheKey(chat.getId(), member.getId()));
            LOGGER.warn("Failed to query newly joined member {} in chat {}: {}",
                    member.getId(), chat.getId(), e.getMessage());
            return;
        }

        ModerationAction action = EntityActionResolver.resolve(configuration.joinProtectionBehavior(), query);
        boolean actionApplied = EntityActionResolver.apply(context, chat.getId(), member.getId(), action, query);
        if (isBlockRecommendation(query) && configuration.joinProtectionNotificationsEnabled())
        {
            if (configuration.moderatorNotificationsEnabled())
            {
                RegistrationHandler.cacheAdministrators(context, chat);
            }
            Language lang = context.managers().languagePreferences()
                    .getChatLanguage(chat.getId());
            NotificationSender.notify(context, chat.getId(), notificationHtml(context.languages(), lang,
                    chat, member, query, action, actionApplied));
        }
    }

    /**
     * Protects a group join request by evaluating and potentially moderating the request
     * based on the chat's configuration, the user's data, and other contextual information.
     * This method handles automated actions such as approving, declining, or flagging the join
     * request and notifying moderators when necessary.
     *
     * @param context the context that provides access to necessary managers, services, and configurations
     * @param joinRequest the join request containing details about the chat and the user attempting to join
     */
    private static void protectJoinRequest(HandlerContext context, ChatJoinRequest joinRequest)
    {
        Chat chat = joinRequest.getChat();
        User user = joinRequest.getUser();
        if (chat == null || user == null || user.getIsBot() || !isGroup(chat))
        {
            return;
        }

        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(chat.getId());
        FederationService federation = context.federation();
        if (!configuration.enabled() || !configuration.joinProtectionEnabled() || !federation.isAvailable() || !claimJoin(context, chat.getId(), user.getId()))
        {
            return;
        }

        if (!configuration.privacyMode())
        {
            RegistrationHandler.pushUser(federation, user);
        }

        EntityQueryResult query;
        try
        {
            query = federation.queryEntity(user.getId() + "@telegram.org").orElse(null);
        }
        catch (FederationException e)
        {
            context.cache().remove(joinCacheKey(chat.getId(), user.getId()));
            LOGGER.warn("Failed to query join request user {} in chat {}: {}",
                    user.getId(), chat.getId(), e.getMessage());
            return;
        }

        ModerationAction action = EntityActionResolver.resolve(configuration.joinProtectionBehavior(), query);

        // Moderate may decline join requests by blocking a flagged member, and Strict bans them.
        // Passive takes no automated action, so the request is approved for the moderator to review.
        boolean declined = action != ModerationAction.NONE;
        boolean requestHandled;
        if (declined)
        {
            requestHandled = ModerationActions.declineJoinRequest(context, chat.getId(), user.getId());
        }
        else
        {
            requestHandled = ModerationActions.approveJoinRequest(context, chat.getId(), user.getId());
        }

        boolean actionTaken = requestHandled && declined;
        boolean notifyAction = actionTaken || isBlockRecommendation(query);
        if (notifyAction && configuration.joinProtectionNotificationsEnabled())
        {
            if (configuration.moderatorNotificationsEnabled())
            {
                RegistrationHandler.cacheAdministrators(context, chat);
            }
            Language lang = context.managers().languagePreferences()
                    .getChatLanguage(chat.getId());
            NotificationSender.notify(context, chat.getId(),
                    joinRequestNotificationHtml(context.languages(), lang, chat, user, query, action,
                            declined, requestHandled));
        }
    }

    /**
     * Evaluates whether the given query recommends blocking an entity, either
     * temporarily or permanently.
     *
     * @param query the result of an entity query, which may include suggested actions
     *              regarding the entity; can be null
     * @return {@code true} if the query recommends temporarily or permanently blocking
     *         the entity, {@code false} otherwise
     */
    private static boolean isBlockRecommendation(EntityQueryResult query)
    {
        if (query == null)
        {
            return false;
        }
        return query.suggestedAction() == SuggestedAction.TEMPORARILY_BLOCK_ENTITY
                || query.suggestedAction() == SuggestedAction.PERMANENTLY_BLOCK_ENTITY;
    }

    /**
     * Determines if a chat member is transitioning from a non-member state to a member state.
     *
     * @param membership the updated chat membership information containing the old and new member states;
     *                   may be {@code null}
     * @return {@code true} if the chat member was previously not a member and is now a member,
     *         {@code false} otherwise
     */
    private static boolean isJoiningMember(ChatMemberUpdated membership)
    {
        if (membership == null)
        {
            return false;
        }
        return isNotMember(membership.getOldChatMember()) && isMember(membership.getNewChatMember());
    }

    /**
     * Determines whether the specified chat member has the status of a regular member.
     *
     * @param member the chat member whose membership status is to be evaluated;
     *               may be an instance of {@link ChatMemberRestricted} or another
     *               subclass of {@link ChatMember}, or {@code null}.
     * @return {@code true} if the specified chat member is a regular member,
     *         {@code false} otherwise.
     */
    private static boolean isMember(ChatMember member)
    {
        if (member instanceof ChatMemberRestricted restricted)
        {
            return Boolean.TRUE.equals(restricted.getIsMember());
        }
        return member != null && "member".equals(member.getStatus());
    }

    /**
     * Determines if the provided chat member is not currently a member of the chat.
     * This method evaluates whether the member is in a non-member state, such as
     * having left or being kicked, or, in the case of a restricted member,
     * explicitly not marked as a member.
     *
     * @param member the chat member whose membership status is to be checked;
     *               may be an instance of {@link ChatMemberRestricted} or another
     *               subclass of {@link ChatMember}, or {@code null}.
     * @return {@code true} if the specified chat member is not currently a member;
     *         {@code false} otherwise.
     */
    private static boolean isNotMember(ChatMember member)
    {
        if (member instanceof ChatMemberRestricted restricted)
        {
            return !Boolean.TRUE.equals(restricted.getIsMember());
        }
        return member != null && ("left".equals(member.getStatus()) || "kicked".equals(member.getStatus()));
    }

    /**
     * Determines whether the specified chat is a group or a supergroup.
     *
     * @param chat the chat whose type is to be evaluated; cannot be null
     * @return {@code true} if the chat is of type "group" or "supergroup",
     *         {@code false} otherwise
     */
    private static boolean isGroup(Chat chat)
    {
        return "group".equals(chat.getType()) || "supergroup".equals(chat.getType());
    }

    /**
     * Attempts to claim the ability to process a join event for a specific member
     * in a specific chat. This method ensures that the claim is exclusive, allowing
     * only one handler instance to handle the event for the given chat and member.
     *
     * @param context the handler context providing access to configuration and cache
     * @param chatId the unique identifier of the chat where the event occurred
     * @param memberId the unique identifier of the member attempting to join or process
     * @return {@code true} if the claim was successfully acquired, {@code false} otherwise
     */
    private static boolean claimJoin(HandlerContext context, long chatId, long memberId)
    {
        AtomicBoolean claim = (AtomicBoolean) context.cache().get(joinCacheKey(chatId, memberId),
                ignored -> new AtomicBoolean(true));
        return Objects.requireNonNull(claim).compareAndSet(true, false);
    }

    /**
     * Constructs a cache key for storing or retrieving data related to a specific member
     * in a specific chat. The key is formed by concatenating a predefined prefix, the chat ID,
     * and the member ID, separated by a colon.
     *
     * @param chatId the unique identifier of the chat
     * @param memberId the unique identifier of the member
     * @return a string representing the cache key for the given chat and member
     */
    private static String joinCacheKey(long chatId, long memberId)
    {
        return JOIN_CLAIM_CACHE_PREFIX + chatId + ':' + memberId;
    }

    /**
     * Generates an HTML-formatted notification message for join protection events in a chat.
     *
     * @param languageManager the manager that provides localized text for the specified language
     * @param lang the language in which the notification should be displayed
     * @param chat the chat where the event occurred, containing information such as its title and ID
     * @param member the user who triggered the join protection event
     * @param query the result of a query evaluating the user's behavior or other attributes
     * @param action the moderation action proposed or taken as part of the join protection process
     * @param actionApplied a flag indicating if the proposed moderation action was applied
     * @return a string containing the formatted notification text in the specified language
     */
    static String notificationHtml(LanguageManager languageManager, Language lang, Chat chat, User member, EntityQueryResult query, ModerationAction action, boolean actionApplied)
    {
        return joinNotificationTitle(languageManager, lang, action, actionApplied) + '\n' +
                languageManager.get(lang, "join_protection", "chat_label", NotificationFormatter.chatReference(chat.getTitle(), chat.getId(), lang)) + '\n' +
                languageManager.get(lang, "join_protection", "offender_label", NotificationFormatter.userMention(member, lang)) + '\n' +
                languageManager.get(lang, "join_protection", "action_recommendation", MessageHelper.displayName(query.suggestedAction()) + ".") + '\n' +
                languageManager.get(lang, "join_protection", "action_taken", notificationAction(languageManager, lang, action, actionApplied)) + "\n\n" +
                languageManager.get(lang, "join_protection", "no_content");
    }

    /**
     * Generates an HTML-based notification message for a join request.
     * <p>
     * This method constructs a notification message in HTML format to notify about the status
     * of a user’s join request to the specified chat. The notification includes details
     * such as the chat title, user information, recommended actions, and the action taken
     * by moderation.
     *
     * @param languageManager An instance of {@code LanguageManager} used for retrieving localized strings.
     * @param lang The {@code Language} to be used for the notification content.
     * @param chat The {@code Chat} instance representing the chat the join request pertains to.
     * @param user The {@code User} who sent the join request*/
    static String joinRequestNotificationHtml(LanguageManager languageManager, Language lang, Chat chat,
                                              User user, EntityQueryResult query, ModerationAction action,
                                              boolean declined, boolean requestHandled)
    {
        StringBuilder html = new StringBuilder(
                joinRequestNotificationTitle(languageManager, lang, declined)).append('\n');
        html.append(languageManager.get(lang, "join_protection", "chat_label",
                NotificationFormatter.chatReference(chat.getTitle(), chat.getId(), lang))).append('\n');
        html.append(languageManager.get(lang, "join_protection", "user_label",
                NotificationFormatter.userMention(user, lang))).append('\n');
        if (isBlockRecommendation(query))
        {
            html.append(languageManager.get(lang, "join_protection", "action_recommendation",
                    MessageHelper.displayName(query.suggestedAction()) + ".")).append('\n');
        }
        html.append(languageManager.get(lang, "join_protection", "action_taken",
                joinRequestNotificationAction(languageManager, lang, declined, action, requestHandled))).append('\n');
        return html.toString();
    }

    /**
     * Generates a notification title based on the specified language, action, and whether the action was applied.
     *
     * @param languageManager the language manager used to fetch localized strings
     * @param lang the language in which the title should be generated
     * @param action the moderation action (e.g., ban, restrict, or none)
     * @param actionApplied whether the moderation action has been applied
     * @return the localized notification title based on the provided parameters
     */
    private static String joinNotificationTitle(LanguageManager languageManager, Language lang, ModerationAction action, boolean actionApplied)
    {
        String key = "title_blocked";
        if (actionApplied)
        {
            key = switch (action)
            {
                case TEMPORARY_BAN, PERMANENT_BAN -> "title_banned";
                case TEMPORARY_RESTRICT, PERMANENT_RESTRICT -> "title_restricted";
                case NONE -> "title_blocked";
            };
        }
        return languageManager.get(lang, "join_protection", key);
    }

    /**
     * Determines the appropriate notification message key based on the moderation action
     * and other contextual parameters, and retrieves the localized message.
     *
     * @param languageManager The LanguageManager instance used to fetch localized messages.
     * @param lang The target language in which the notification should be localized.
     * @param action The ModerationAction performed, which influences the notification type.
     * @param actionApplied A flag indicating whether the moderation action was successfully applied.
     * @return A localized notification message string corresponding to the given parameters.
     * @throws IllegalStateException If an unexpected moderation action is encountered.
     */
    private static String notificationAction(LanguageManager languageManager, Language lang, ModerationAction action, boolean actionApplied)
    {
        String key;

        if (action == ModerationAction.NONE)
        {
            key = "action_none_passive";
        }
        else if (!actionApplied)
        {
            key = "action_failed";
        }
        else
        {
            key = switch (action)
            {
                case TEMPORARY_RESTRICT -> "action_temp_restricted";
                case PERMANENT_RESTRICT -> "action_restricted";
                case TEMPORARY_BAN -> "action_temp_banned";
                case PERMANENT_BAN -> "action_banned";
                default -> throw new IllegalStateException("Unexpected value: " + action);
            };
        }
        return languageManager.get(lang, "join_protection", key);
    }

    /**
     * Generates the notification title for a join request, based on the provided language and status.
     *
     * @param languageManager The LanguageManager instance used to fetch localized text.
     * @param lang The language to retrieve the localized text for.
     * @param declined A boolean indicating whether the request was declined (true) or approved (false).
     * @return A string representing the localized notification title for the join request.
     */
    private static String joinRequestNotificationTitle(LanguageManager languageManager, Language lang, boolean declined)
    {
        return languageManager.get(lang, "join_protection", declined ? "request_declined" : "request_approved");
    }

    /**
     * Generates a localized notification message based on the status of a join request action.
     * The method creates a message key depending on whether the request was handled,
     * declined, or approved, and the type of moderation action applied.
     *
     * @param languageManager The manager used to retrieve language-specific messages.
     * @param lang The language in which the notification message should be localized.
     * @param declined Indicates whether the join request was declined.
     * @param action The type of moderation action applied to the join request.
     * @param requestHandled Indicates if the join request handling process was completed successfully.
     *
     * @return A localized string message corresponding to the join request's action and outcome.
     */
    private static String joinRequestNotificationAction(LanguageManager languageManager, Language lang,
                                                        boolean declined, ModerationAction action,
                                                        boolean requestHandled)
    {
        String key;
        if (!requestHandled)
        {
            key = "request_action_failed";
        }
        else if (declined)
        {
            key = "request_action_declined";
        }
        else
        {
            key = switch (action)
            {
                case TEMPORARY_RESTRICT -> "request_action_approved_temp_restricted";
                case PERMANENT_RESTRICT -> "request_action_approved_restricted";
                default -> "request_action_approved";
            };
        }
        return languageManager.get(lang, "join_protection", key);
    }
}
