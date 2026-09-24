package net.nosial.spb.handlers.group;

import net.nosial.spb.classes.UpdateDispatcher;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.jfederation.records.ScannedContent;
import net.nosial.spb.enums.ModerationAction;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.objects.Language;
import net.nosial.spb.utilities.EntityActionResolver;
import net.nosial.spb.utilities.HostExtractor;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.classes.sessions.FalsePositiveReportSessionManager;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.AdminInfo;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.MediaGroupState;
import net.nosial.spb.objects.context.FalsePositiveReportContext;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.spb.objects.ScanningOutcome;
import net.nosial.spb.utilities.MessageContent;
import org.slf4j.Logger;
import net.nosial.spb.utilities.MessageHelper;
import net.nosial.spb.utilities.ModerationActions;
import net.nosial.spb.utilities.ReportAttachments;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.nio.file.Files;
import net.nosial.spb.classes.notifications.NotificationSender;

/**
 * Checks member messages against Federation and acts on what comes back.
 *
 * <p>Runs as an observer, after {@link RegistrationHandler} has recorded who sent the message and
 * refreshed the chat's administrator list, both of which the moderation decision depends on. It
 * never consumes an update: a {@code /report} still reaches its own handler after the message
 * carrying it has been scanned.
 *
 * <p>What it does with a finding is the chat's {@link net.nosial.spb.enums.ScanningBehavior}:
 * notify only, delete and restrict, or delete and ban.
 *
 * <p>Alongside the scan, the domain names and IP addresses mentioned in the text or caption are
 * published to Federation as host entities (see {@link HostExtractor}), so the database learns
 * about the hosts being linked to, not only the users linking them.
 */
@UpdateHandler(value = UpdateType.MESSAGE, mode = DispatchMode.OBSERVE, priority = 950)
public final class ScanningHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanningHandler.class);
    private static final String CAUTION_USER_CACHE_PREFIX = "caution-user:";
    private static final String SCAN_STATE_CACHE_PREFIX = "scan-state:";
    private static final String TEXT_RESTRICTED_CACHE_PREFIX = "text-restricted:";
    private static final String PUBLISHED_HOST_CACHE_PREFIX = "published-host:";

    /**
     * Processes the given update before routing, tracking the sender when the update carries a
     * message and refreshing the administrator cache for group messages.
     *
     * <p>Tracking and cache failures are logged and swallowed: a database or Telegram hiccup must
     * never prevent the update from reaching its command handler.
     *
     * @param context the per-update command context
     */
    @Override
    public void handle(HandlerContext context)
    {
        Update update = context.update();
        Message message = update.getMessage();
        if (message == null)
        {
            LOGGER.debug("Update {} ignored by update handler: no message", update.getUpdateId());
            return;
        }

        User author = message.getFrom();
        if (author == null)
        {
            LOGGER.debug("Update {} ignored by update handler: message without an author", update.getUpdateId());
            return;
        }

        scanMessageContent(context, message);
        publishContentHosts(context, message);

        LOGGER.debug("Update {} scanned, author {}", update.getUpdateId(), author.getId());
    }

    /**
     * Scans message content and queries the message author as one unified moderation operation.
     *
     * <p>When scanning is enabled, the bot submits scannable content and, outside Privacy Mode,
     * queries the author's Federation entity. The selected {@link ScanningBehavior} governs
     * automated content deletion and entity moderation.
     *
     * @param context the per-update command context
     * @param message the incoming message
     */
    private void scanMessageContent(HandlerContext context, Message message)
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(message.getChatId());
        if (!shouldModerate(context, message, configuration))
        {
            return;
        }

        if (isKnownAdministratorOrBot(context, message))
        {
            return;
        }

        MediaGroupState mediaGroup = UpdateDispatcher.mediaGroup(context, message);
        if (isRestrictedMediaGroup(mediaGroup, message))
        {
            ModerationActions.deleteMessage(context, message);
            return;
        }

        ScannedContent scannedContent = null;
        if (shouldScan(context, message, configuration))
        {
            try
            {
                scannedContent = context.federation().scanContent(
                        MessageContent.buildScanInput(message, configuration.privacyMode()),
                        configuration.privacyMode() ? null : message.getFrom().getId() + "@telegram.org");
                LOGGER.info("Scanned message {} in chat {} from user {}: risk_score={}, suggested_action={}, "
                                + "classification={}, resolved_entities={}, scanner_results={}",
                        message.getMessageId(), message.getChatId(), message.getFrom().getId(), scannedContent.riskScore(),
                        scannedContent.suggestedAction(), scannedContent.getClassification(),
                        scannedContent.getResolvedEntities().size(), scannedContent.scanResults());
            }
            catch (FederationException e)
            {
                LOGGER.warn("Failed to scan message {} in chat {}: {}",
                        message.getMessageId(), message.getChatId(), e.getMessage());
            }
        }

        EntityQueryResult entityQuery = null;
        if (!configuration.privacyMode())
        {
            try
            {
                entityQuery = context.federation()
                        .queryEntity(message.getFrom().getId() + "@telegram.org").orElse(null);
            }
            catch (FederationException e)
            {
                LOGGER.warn("Failed to query author {} in chat {}: {}",
                        message.getFrom().getId(), message.getChatId(), e.getMessage());
            }
        }

        if (isRestrictedMediaGroup(mediaGroup, message))
        {
            ModerationActions.deleteMessage(context, message);
            return;
        }

        SuggestedAction contentSuggestion = scannedContent != null ? scannedContent.suggestedAction() : null;
        SuggestedAction entitySuggestion = entityQuery != null ? entityQuery.suggestedAction() : null;
        String userCautionAction = (contentSuggestion == SuggestedAction.CAUTION || entitySuggestion == SuggestedAction.CAUTION)
                ? "CAUTION" : null;
        if (context.cache() != null)
        {
            String cautionKey = CAUTION_USER_CACHE_PREFIX + message.getChatId() + ":" + message.getFrom().getId();
            context.cache().put(cautionKey, userCautionAction);
        }

        ScanningBehavior behavior = configuration.scanningBehavior();
        if (behavior != ScanningBehavior.PASSIVE && !isRegularUser(context, message))
        {
            LOGGER.info("Skipped automated moderation for administrator or bot {} in chat {}",
                    message.getFrom().getId(), message.getChatId());
            return;
        }

        ModerationAction entityAction = entityAction(behavior, entityQuery);
        if (!canApplyEntityAction(entityAction, entityQuery))
        {
            entityAction = ModerationAction.NONE;
        }

        boolean deleteContent = context.telegramClient() != null
                && deletesContent(context, behavior, contentSuggestion, entitySuggestion, message);
        List<ReportAttachment> falsePositiveAttachments = configuration.scanningNotificationsEnabled()
                ? captureReportAttachments(context, message) : List.of();
        List<Integer> deletedMessageIds = List.of();
        if (deleteContent)
        {
            if ((contentSuggestion == SuggestedAction.CAUTION || entitySuggestion == SuggestedAction.CAUTION)
                    && mediaGroup != null && mediaGroup.restrict())
            {
                deletedMessageIds = deleteMediaGroup(context, message, mediaGroup);
            }
            else if (ModerationActions.deleteMessage(context, message))
            {
                deletedMessageIds = List.of(message.getMessageId());
            }
        }
        boolean entityActionApplied = EntityActionResolver.apply(context, message.getChatId(), message.getFrom().getId(), entityAction, entityQuery);

        boolean textRestrictionApplied = false;
        if (behavior == ScanningBehavior.STRICT && entitySuggestion == SuggestedAction.CAUTION && !isTextRestricted(context, message))
        {
            textRestrictionApplied = ModerationActions.restrictMediaOnly(context, message.getChatId(), message.getFrom().getId());
            if (textRestrictionApplied)
            {
                markTextRestricted(context, message);
            }
        }

        if (behavior == ScanningBehavior.PASSIVE && configuration.scanningNotificationsEnabled() && !suggestedActionStateChanged(context, message, contentSuggestion, entitySuggestion))
        {
            return;
        }
        if (behavior == ScanningBehavior.PASSIVE && !configuration.scanningNotificationsEnabled())
        {
            return;
        }
        if (behavior != ScanningBehavior.PASSIVE && deletedMessageIds.isEmpty() && !entityActionApplied && !textRestrictionApplied)
        {
            return;
        }
        sendScanningObservation(context, configuration, message,
                new ScanningOutcome(contentSuggestion, entitySuggestion, deletedMessageIds, deleteContent, entityAction, entityActionApplied, textRestrictionApplied),
                falsePositiveAttachments);
    }
    /**
     * Publishes the domain names and IP addresses mentioned in the message as Federation host
     * entities.
     *
     * <p>Runs after moderation so that publishing never delays a deletion. It follows scanning's
     * eligibility, whoever the author is, since recording a host passes no judgement on the person
     * who mentioned it. Privacy Mode skips it: there, scanning promises to send nothing but the
     * text itself. Hosts published recently are not published again, and failures are logged and
     * skipped — one rejected host must not stop the rest.
     *
     * @param context the per-update command context
     * @param message the incoming message
     */
    private static void publishContentHosts(HandlerContext context, Message message)
    {
        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(message.getChatId());
        if (configuration.privacyMode()
                || !context.federation().isAuthenticated()
                || !shouldScan(context, message, configuration))
        {
            return;
        }

        for (String host : HostExtractor.extract(message))
        {
            String cacheKey = PUBLISHED_HOST_CACHE_PREFIX + host;
            if (context.cache() != null && context.cache().getIfPresent(cacheKey) != null)
            {
                continue;
            }

            try
            {
                context.federation().publishEntity(host, null, null);
                if (context.cache() != null)
                {
                    context.cache().put(cacheKey, Boolean.TRUE);
                }
                LOGGER.debug("Published host entity {} from message {} in chat {}",
                        host, message.getMessageId(), message.getChatId());
            }
            catch (FederationException e)
            {
                LOGGER.warn("Failed to publish host entity {} from message {} in chat {}: {}",
                        host, message.getMessageId(), message.getChatId(), e.getMessage());
            }
        }
    }

    /**
     * Returns whether the message is eligible for Federation scanning. Only enabled group chats
     * with scanning enabled, scannable text or captions, and a bot administrator eligible to
     * change chat information may scan.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @param configuration the persistent configuration for the message chat
     * @return {@code true} when scanning should run
     */
    static boolean shouldScan(HandlerContext context, Message message, ChatConfiguration configuration)
    {
        String content = MessageContent.contentToScan(message);
        return shouldModerate(context, message, configuration)
                && configuration.scanningEnabled()
                && content != null
                && !content.isBlank();
    }

    private static boolean shouldModerate(HandlerContext context, Message message, ChatConfiguration configuration)
    {
        String chatType = message.getChat().getType();
        if ((!chatType.equals("group") && !chatType.equals("supergroup"))
                || !configuration.enabled()
                || !configuration.scanningEnabled()
                || !context.federation().isAvailable())
        {
            return false;
        }

        List<AdminInfo> administrators = context.chatAdmins().getIfPresent(message.getChatId());
        return administrators != null && administrators.stream().anyMatch(a -> a.id() == context.botUserId());
    }

    /**
     * Decides whether flagged content should be deleted for the configured behavior.
     *
     * <p>Blocked content is deleted by Moderate and Strict behaviors. Cautioned content is
     * deleted only when it is anything other than a plain text message without URLs - photos,
     * videos, stickers, other media, links, or messages without text are removed, while basic
     * text messages with no URLs are kept. Passive only reports it.
     *
     * @param behavior the configured scanning behavior
     * @param contentSuggestion the content suggestion
     * @param entitySuggestion the entity suggestion
     * @param message the incoming message
     * @return whether content deletion is requested
     */
    static boolean deletesContent(HandlerContext context, ScanningBehavior behavior, SuggestedAction contentSuggestion, SuggestedAction entitySuggestion, Message message)
    {
        boolean contentBlocked = contentSuggestion == SuggestedAction.BLOCK_CONTENT;
        boolean currentCaution = contentSuggestion == SuggestedAction.CAUTION
                || entitySuggestion == SuggestedAction.CAUTION;
        if (contentBlocked)
        {
            return behavior != ScanningBehavior.PASSIVE;
        }
        boolean isCautionState = currentCaution || isPreviousCaution(message, context);
        if (!isCautionState)
        {
            return false;
        }
        return behavior != ScanningBehavior.PASSIVE && notPlainText(message);
    }

    /**
     * Determines whether a given message is not plain text. A message is considered "not plain text"
     * if it contains media, links, or has no textual content or only blank text.
     *
     * @param message the message to evaluate
     * @return {@code true} if the message is not plain text; {@code false} otherwise
     */
    private static boolean notPlainText(Message message)
    {
        String text = MessageContent.contentToScan(message);
        return MessageContent.hasMedia(message) || MessageContent.hasLink(message) || text == null || text.isBlank();
    }

    /**
     * Determines whether the previous state associated with the user in the given chat is marked as "CAUTION".
     *
     * @param message the message object containing user and chat information
     * @param context the handler context that provides access to the cache
     * @return true if the previous state is marked as "CAUTION", false otherwise
     */
    private static boolean isPreviousCaution(Message message, HandlerContext context)
    {
        if (context == null || context.cache() == null)
        {
            return false;
        }
        String key = CAUTION_USER_CACHE_PREFIX + message.getChatId() + ":" + message.getFrom().getId();
        String cached = (String) context.cache().getIfPresent(key);
        return "CAUTION".equals(cached);
    }

    /**
     * Determines whether the state of the suggested action has changed.
     * <p>
     * Checks the provided `contentSuggestion` and `entitySuggestion` against the cached state
     * associated with the given `message` and its sender. If the cached state has not been
     * set or differs from the current suggestions, the method updates the cache and returns true.
     *
     * @param context the context containing the cache to store and retrieve state information.
     * @param message the message, including its sender and chat details, used to generate a unique cache key.
     * @param contentSuggestion the suggested action based on the content of the message.
     * @param entitySuggestion the suggested action based on the entity in the message.
     * @return true if the suggested action state has changed, otherwise false.
     */
    static boolean suggestedActionStateChanged(HandlerContext context, Message message, SuggestedAction contentSuggestion, SuggestedAction entitySuggestion)
    {
        if (context.cache() == null)
        {
            return true;
        }
        String key = SCAN_STATE_CACHE_PREFIX + message.getChatId() + ":" + message.getFrom().getId();
        String action = contentSuggestion + ":" + entitySuggestion;
        @SuppressWarnings("unchecked")
        AtomicReference<Object> state = (AtomicReference<Object>) context.cache()
                .get(key, ignored -> new AtomicReference<>());
        Object previous = Objects.requireNonNull(state).getAndSet(action);
        return !action.equals(previous);
    }

    /**
     * Determines if the text is restricted for a specific user in a chat context.
     *
     * @param context the handler context providing access to the cache and other resources
     * @param message the message object containing chat and user information
     * @return true if the text is restricted, false otherwise
     */
    static boolean isTextRestricted(HandlerContext context, Message message)
    {
        if (context.cache() == null)
        {
            return true;
        }
        String key = TEXT_RESTRICTED_CACHE_PREFIX + message.getChatId() + ":" + message.getFrom().getId();
        return Boolean.TRUE.equals(context.cache().getIfPresent(key));
    }

    /**
     * Marks the text as restricted by storing an entry in the cache with the specified key.
     * The key is constructed using the chat ID and the sender's ID from the message.
     * This method does nothing if the cache in the provided context is null.
     *
     * @param context the handler context containing the cache for storing the restriction data
     * @param message the message containing the chat ID and sender's ID to construct the cache key
     */
    static void markTextRestricted(HandlerContext context, Message message)
    {
        if (context.cache() == null)
        {
            return;
        }
        String key = TEXT_RESTRICTED_CACHE_PREFIX + message.getChatId() + ":" + message.getFrom().getId();
        context.cache().put(key, Boolean.TRUE);
    }

    /**
     * Performs an entity action based on the provided scanning behavior and entity query result.
     *
     * @param behavior the scanning behavior that defines the rules or conditions for the action to be executed.
     * @param query the result of an entity query, representing the entity or entities to act upon.
     * @return a ModerationAction representing the resolved action for the specified behavior and query.
     */
    static ModerationAction entityAction(ScanningBehavior behavior, EntityQueryResult query)
    {
        return EntityActionResolver.resolve(behavior, query);
    }

    /**
     * Decides whether a resolved entity action may be applied to the user under the agreed
     * restriction policy: a permanent action is always allowed, while a temporary action is only
     * allowed when the Federation server supplies a suggested lift timestamp.
     *
     * @param action the resolved entity moderation action
     * @param query the entity query result used to decide the action
     * @return {@code true} when the action may be applied
     */
    static boolean canApplyEntityAction(ModerationAction action, EntityQueryResult query)
    {
        if (action == ModerationAction.NONE)
        {
            return false;
        }
        if (action == ModerationAction.PERMANENT_BAN || action == ModerationAction.PERMANENT_RESTRICT)
        {
            return true;
        }
        return query != null && query.suggestedLiftTimestamp() != null;
    }

    /**
     * Determines whether the author of a given message is a known administrator or a bot.
     *
     * @param context the handler context containing cached chat administrator information
     * @param message the message whose author is being evaluated
     * @return {@code true} if the author is a bot or is listed as an administrator in the cached data,
     *         {@code false} otherwise
     */
    private static boolean isKnownAdministratorOrBot(HandlerContext context, Message message)
    {
        User author = message.getFrom();
        if (author == null || author.getIsBot())
        {
            return true;
        }
        List<AdminInfo> cachedAdministrators = context.chatAdmins().getIfPresent(message.getChatId());
        return cachedAdministrators != null && cachedAdministrators.stream().anyMatch(a -> a.id() == author.getId());
    }

    /**
     * Checks whether a user is a regular user (i.e., not an owner, administrator, or bot) in the context
     * of a given message and chat.
     *
     * @param context the context containing necessary resources such as the Telegram client for API interaction.
     * @param message the message whose sender is being evaluated.
     * @return {@code true} if the user is a regular chat member (neither owner, administrator, nor bot),
     *         {@code false} otherwise or if the check cannot be completed due to an exception.
     */
    private static boolean isRegularUser(HandlerContext context, Message message)
    {
        User author = message.getFrom();
        if (isKnownAdministratorOrBot(context, message))
        {
            return false;
        }
        try
        {
            ChatMember member = context.telegramClient().execute(GetChatMember.builder()
                    .chatId(String.valueOf(message.getChatId()))
                    .userId(author.getId())
                    .build());
            return !(member instanceof ChatMemberOwner) && !(member instanceof ChatMemberAdministrator);
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Unable to verify whether user {} is an administrator in chat {}; skipping moderation: {}",
                    author.getId(), message.getChatId(), e.getMessage());
            return false;
        }
    }

    /**
     * Captures the message's report attachments together with their raw bytes so they can be
     * submitted later even after the message (and its Telegram file references) have been deleted.
     * Must be invoked before any deletion of {@code message}.
     *
     * @param context the per-update command context
     * @param message the incoming message
     * @return the attachments with eagerly captured content, or the lazy file references when the
     *         bytes could not be downloaded
     */
    static List<ReportAttachment> captureReportAttachments(HandlerContext context, Message message)
    {
        List<ReportAttachment> attachments = ReportAttachments.forReport(context, message);
        if (attachments.isEmpty() || context.telegramClient() == null)
        {
            return attachments;
        }
        List<ReportAttachment> enriched = new ArrayList<>(attachments.size());
        for (ReportAttachment attachment : attachments)
        {
            enriched.add(ReportAttachment.withContent(attachment, downloadAttachmentBytes(context, attachment)));
        }
        return List.copyOf(enriched);
    }

    /**
     * Downloads the bytes of a given report attachment from Telegram using the provided handler context.
     *
     * @param context the handler context, which includes the Telegram client for performing the download
     * @param attachment the report attachment containing the file ID of the file to be downloaded
     * @return a byte array containing the file's content, or null if the download fails
     */
    private static byte[] downloadAttachmentBytes(HandlerContext context, ReportAttachment attachment)
    {
        File downloaded = null;
        try
        {
            org.telegram.telegrambots.meta.api.objects.File telegramFile = context.telegramClient().execute(GetFile.builder().fileId(attachment.fileId()).build());
            downloaded = context.telegramClient().downloadFile(telegramFile);
            return Files.readAllBytes(downloaded.toPath());
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to cache Telegram attachment {} for false-positive report: {}",
                    attachment.fileId(), e.getMessage());
            return null;
        }
        finally
        {
            if (downloaded != null)
            {
                try
                {
                    Files.deleteIfExists(downloaded.toPath());
                }
                catch (Exception e)
                {
                    LOGGER.debug("Could not delete temporary Telegram download {}: {}", downloaded, e.getMessage());
                }
            }
        }
    }

    /**
     * Determines whether the given MediaGroupState is restricted and authored
     * by the sender of the provided message.
     *
     * @param mediaGroup the MediaGroupState to check; may be null
     * @param message the Message containing the author information
     * @return true if the mediaGroup is not null, is restricted, and is authored
     *         by the sender of the provided message; false otherwise
     */
    static boolean isRestrictedMediaGroup(MediaGroupState mediaGroup, Message message)
    {
        return mediaGroup != null && mediaGroup.isRestricted() && mediaGroup.authorId() == message.getFrom().getId();
    }

    /**
     * Deletes all messages in the specified media group.
     *
     * @param context     The handler context providing access to messaging services and resources.
     * @param message     The original message associated with the media group.
     * @param mediaGroup  The media group containing the IDs of the messages to be deleted.
     * @return A list of message IDs that were successfully deleted.
     */
    private static List<Integer> deleteMediaGroup(HandlerContext context, Message message, MediaGroupState mediaGroup)
    {
        List<Integer> deletedMessageIds = new ArrayList<>();
        for (int messageId : mediaGroup.messageIds())
        {
            if (ModerationActions.deleteMessage(context, message.getChatId(), messageId))
            {
                deletedMessageIds.add(messageId);
            }
        }
        return deletedMessageIds;
    }

    /**
     * Sends a scanning observation notification to the chat, provided that scanning notifications
     * are enabled in the chat configuration. This method checks the language preferences of the chat,
     * the authentication status of the federation, and creates a report for false positive messages
     * if applicable, attaching the necessary context.
     *
     * @param context the current {@link HandlerContext} providing access to framework utilities,
     *                managers, and session methods.
     * @param configuration the {@link ChatConfiguration} associated with the current chat,
     *                      containing settings like whether scanning notifications are enabled.
     * @param message the {@link Message} being scanned, which potentially triggers a scanning observation.
     * @param outcome the result of the scanning operation, represented as a {@link ScanningOutcome}.
     * @param falsePositiveAttachments a list of {@link ReportAttachment} entities that might be relevant
     *                                 for reporting a false positive case.
     */
    private void sendScanningObservation(HandlerContext context, ChatConfiguration configuration, Message message, ScanningOutcome outcome, List<ReportAttachment> falsePositiveAttachments)
    {
        if (!configuration.scanningNotificationsEnabled())
        {
            return;
        }

        Language lang = context.managers().languagePreferences().getChatLanguage(message.getChatId());

        // Reporting a false positive submits as the bot itself, which the server refuses
        // unconditionally without client permissions. Offering the button anyway would only ever
        // end in a failure alert, so it — and the session it would need — is skipped entirely.
        InlineKeyboardMarkup markup = null;
        if (context.federation().isAuthenticated())
        {
            FalsePositiveReportContext falsePositive = context.sessions().falsePositive().create(message, MessageContent.contentToScan(message), falsePositiveAttachments);
            markup = falsePositiveMarkup(context.languages(), lang, falsePositive);
        }

        NotificationSender.notify(context, message.getChatId(), scanningNotificationHtml(context.languages(), lang, configuration, message, outcome), markup);
    }

    /**
     * Builds the one-button keyboard that lets a moderator flag a scan result as wrong.
     *
     * @param languageManager the translations
     * @param lang the language of the chat the notification goes to
     * @param session the one-shot action the button carries
     * @return the inline keyboard
     */
    static InlineKeyboardMarkup falsePositiveMarkup(LanguageManager languageManager, Language lang, FalsePositiveReportContext session)
    {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(languageManager.get(lang, "buttons", "report_false_positive"))
                        .callbackData(FalsePositiveReportSessionManager.callbackData(session))
                        .build()))
                .build();
    }

    /**
     * Generates an HTML-formatted notification string for a scanning event based on the given parameters.
     *
     * @param languageManager The LanguageManager instance used to retrieve localized strings.
     * @param lang The language in which the notification should be generated.
     * @param configuration The ChatConfiguration object containing the chat settings.
     * @param message The Message object that triggered the scanning event.
     * @param outcome The ScanningOutcome object containing the results of the scanning.
     * @return A formatted HTML string representing the scanning notification.
     */
    static String scanningNotificationHtml(LanguageManager languageManager, Language lang, ChatConfiguration configuration, Message message, ScanningOutcome outcome)
    {
        StringBuilder recommendation = new StringBuilder();
        if (outcome.contentSuggestion() != null)
        {
            recommendation.append(MessageHelper.displayName(outcome.contentSuggestion())).append('.');
        }
        if (outcome.entitySuggestion() != null)
        {
            if (!recommendation.isEmpty())
            {
                recommendation.append(' ');
            }
            recommendation.append(languageManager.get(lang, "scanning", "member_prefix", MessageHelper.displayName(outcome.entitySuggestion())));
        }

        return scanningNotificationTitle(languageManager, lang, outcome) + '\n' +
                languageManager.get(lang, "scanning", "chat_label", NotificationFormatter.chatReference(message.getChat().getTitle(), message.getChatId(), lang)) + '\n' +
                languageManager.get(lang, "scanning", "offender_label", NotificationFormatter.userMention(message.getFrom(), lang)) + '\n' +
                languageManager.get(lang, "scanning", "action_recommendation", recommendation) + '\n' +
                languageManager.get(lang, "scanning", "action_taken", scanningActionTaken(languageManager, lang, configuration, outcome)) + "\n\n";
    }

    /**
     * Generates the notification title for a scanning operation based on the outcome
     * and action taken during the scanning process.
     *
     * @param languageManager the LanguageManager to retrieve localized strings
     * @param lang the language in which the notification title is to be retrieved
     * @param outcome the ScanningOutcome representing the result of the scanning operation
     * @return a localized string representing the appropriate notification title
     */
    private static String scanningNotificationTitle(LanguageManager languageManager, Language lang, ScanningOutcome outcome)
    {
        String key;
        if (!outcome.deletedMessageIds().isEmpty())
        {
            key = "title_content_deleted";
        }
        else if (outcome.entityActionApplied() && (outcome.entityAction() == ModerationAction.PERMANENT_BAN
                || outcome.entityAction() == ModerationAction.TEMPORARY_BAN))
        {
            key = "title_member_banned";
        }
        else if (outcome.entityActionApplied() || outcome.textRestrictionApplied())
        {
            key = "title_member_restricted";
        }
        else
        {
            key = "title_scan_match";
        }

        return languageManager.get(lang, "scanning", key);
    }

    /**
     * Determines and constructs a summary string describing the actions taken during a content scanning operation.
     * This includes handling outcomes such as message deletion, restriction requests, or other moderation actions
     * based on the scanning outcome provided.
     *
     * @param languageManager The {@code LanguageManager} instance used to retrieve localized messages for actions.
     * @param lang The {@code Language} specifying the language to be used for the localized messages.
     * @param configuration The {@code ChatConfiguration} containing settings that influence moderation behavior.
     * @param outcome The {@code ScanningOutcome} object providing details on the results of the scanning process,
     *                        including actions performed or requested.
     * @return A formatted string summarizing the moderation actions taken (or not taken) during scanning,
     *         localized to the specified language.
     */
    private static String scanningActionTaken(LanguageManager languageManager, Language lang, ChatConfiguration configuration, ScanningOutcome outcome)
    {
        if (outcome.automatedModerationSkipped())
        {
            return languageManager.get(lang, "scanning", "action_skipped");
        }
        List<String> actions = new ArrayList<>();
        if (!outcome.deletedMessageIds().isEmpty())
        {
            String ids = outcome.deletedMessageIds().stream().map(id -> "<code>" + id + "</code>")
                    .collect(java.util.stream.Collectors.joining(", "));
            actions.add(languageManager.get(lang, "scanning",
                    outcome.deletedMessageIds().size() == 1 ? "action_deleted_single" : "action_deleted_multiple", ids));
        }
        else if (outcome.contentRemovalRequested())
        {
            actions.add(languageManager.get(lang, "scanning", "action_delete_failed"));
        }
        if (outcome.mediaRestrictionRequested())
        {
            actions.add(languageManager.get(lang, "scanning",
                    outcome.mediaRestrictionApplied() ? "action_restricted_text" : "action_restrict_failed"));
        }
        if (outcome.textRestrictionApplied())
        {
            actions.add(languageManager.get(lang, "scanning", "action_restricted_text"));
        }
        if (outcome.entityAction() != ModerationAction.NONE)
        {
            if (outcome.entityActionApplied())
            {
                actions.add(languageManager.get(lang, "scanning", entityActionTakenKey(outcome.entityAction())));
            }
            else
            {
                actions.add(languageManager.get(lang, "scanning", "action_failed_prefix",
                        languageManager.get(lang, "scanning", entityActionDescriptionKey(outcome.entityAction()))));
            }
        }
        if (actions.isEmpty())
        {
            return languageManager.get(lang, "scanning",
                    configuration.scanningBehavior() == ScanningBehavior.PASSIVE
                            ? "action_notification_only" : "action_none_required");
        }
        return String.join("; ", actions) + '.';
    }

    /**
     * Generates a key that corresponds to the moderation action taken on an entity.
     *
     * @param action the ModerationAction that was taken on the entity. This can include actions such as
     *               temporary ban, permanent ban, temporary restriction, permanent restriction, or no action.
     * @return a string key representing the specific moderation action taken.
     */
    private static String entityActionTakenKey(ModerationAction action)
    {
        return switch (action)
        {
            case TEMPORARY_BAN -> "entity_action_temp_banned";
            case PERMANENT_BAN -> "entity_action_banned";
            case TEMPORARY_RESTRICT -> "entity_action_temp_restricted";
            case PERMANENT_RESTRICT -> "entity_action_restricted";
            case NONE -> "entity_action_none";
        };
    }

    /**
     * Constructs a description key for the given moderation action.
     *
     * @param action the moderation action for which the description key is to be generated
     * @return a string representing the description key associated with the specified moderation action
     */
    private static String entityActionDescriptionKey(ModerationAction action)
    {
        return switch (action)
        {
            case TEMPORARY_BAN -> "entity_desc_temp_ban";
            case PERMANENT_BAN -> "entity_desc_ban";
            case TEMPORARY_RESTRICT -> "entity_desc_temp_restrict";
            case PERMANENT_RESTRICT -> "entity_desc_restrict";
            case NONE -> "entity_desc_none";
        };
    }

    

    

    
}