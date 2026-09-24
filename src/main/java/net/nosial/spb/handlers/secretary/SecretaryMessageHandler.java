package net.nosial.spb.handlers.secretary;

import net.nosial.spb.classes.interfaces.EchoCall;
import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.jfederation.records.ScannedContent;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.objects.Language;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.SecretaryContact;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import net.nosial.spb.utilities.MessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.business.BusinessMessagesDeleted;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nosial.spb.classes.notifications.NotificationFormatter;
import net.nosial.spb.objects.database.UserIdentity;
import net.nosial.spb.utilities.MessageHelper;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.business.DeleteBusinessMessages;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Handles the messages that arrive through a Telegram Business connection.
 *
 * <p>This is the working half of secretary mode. A message from somebody the owner has not spoken
 * to before is checked against Federation, the contact is allowed or denied according to the
 * owner's chosen behavior, and the owner is notified with buttons to overturn the decision. A
 * message from an already-allowed contact is left alone; one from a denied contact is deleted
 * without another notification.
 *
 * <p>Turning secretary mode on and off is {@link SecretaryConnectionHandler}'s job, and the
 * settings menu is {@link SecretarySettingsHandler}'s.
 */
@UpdateHandler({UpdateType.BUSINESS_MESSAGE, UpdateType.EDITED_BUSINESS_MESSAGE,
        UpdateType.DELETED_BUSINESS_MESSAGES})
public final class SecretaryMessageHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretaryMessageHandler.class);
    private static final String CONTACT_CLAIM_CACHE_PREFIX = "secretary-contact-claim:";
    private static final int MAX_EVIDENCE_CAPTION_LENGTH = 1_024;

    /**
     * Routes a business-message update to the owning secretary configuration.
     *
     * <p>New and edited business messages are forwarded to the secretary pipeline; deletions are
     * only logged, since nothing is actionable once Telegram has already removed the messages.
     *
     * @param context the update services and configuration
     */
    @Override
    public void handle(HandlerContext context)
    {
        Update update = context.update();
        if (update.hasBusinessMessage())
        {
            processBusinessMessage(context, update.getBusinessMessage());
        }
        else if (update.hasEditedBusinessMessage())
        {
            processBusinessMessage(context, update.getEditedBuinessMessage());
        }
        else if (update.hasDeletedBusinessMessage())
        {
            BusinessMessagesDeleted deleted = update.getDeletedBusinessMessages();
            LOGGER.debug("Business messages {} deleted in connection {} (chat {})",
                    deleted.getMessageIds(), deleted.getBusinessConnectionId(),
                    deleted.getChat() != null ? deleted.getChat().getId() : "unknown");
        }
    }

    /**
     * Processes a business message, analyzing its context, sender, and content to determine
     * the appropriate action, such as allowing, denying, or notifying the owner based on
     * configured secretary behaviors and message analysis results.
     *
     * @param context The {@code HandlerContext} instance that provides access to managers
     *                and configurations required for processing the message.
     * @param message The {@code Message} object representing the business message to be processed,
     *                containing details such as sender, text, and connection information.
     */
    static void processBusinessMessage(HandlerContext context, Message message)
    {
        String connectionId = message.getBusinessConnectionId();
        Optional<Long> owner = ownerForConnection(context, connectionId);
        if (owner.isEmpty())
        {
            owner = context.managers().secretaryConfigurations().bindUnknownConnection(connectionId);
            if (owner.isEmpty())
            {
                LOGGER.debug("Business message {} ignored: secretary mode is not enabled for connection {}", message.getMessageId(), connectionId);
                return;
            }
            LOGGER.info("Business message {} enabled secretary mode: connection {} bound to user {}", message.getMessageId(), connectionId, owner.get());
        }

        long ownerId = owner.get();
        if (isDecisionCommand(message) && message.getFrom() != null && message.getFrom().getId() == ownerId && message.getChatId() != null)
        {
            // In a business chat the chat is the private conversation with the contact, so its
            // id is the contact's user id.
            String command = message.getText().trim().split("\\s+", 2)[0].toLowerCase();
            setContactStatus(context, connectionId, message.getChatId(),
                    command.equals("/allow") || command.startsWith("/allow@") ? SecretaryContactStatus.ALLOWED : SecretaryContactStatus.DENIED);
            return;
        }

        if (message.getFrom() == null)
        {
            LOGGER.debug("Business message {} ignored: no sender", message.getMessageId());
            return;
        }
        User sender = message.getFrom();
        if (sender.getId() == ownerId)
        {
            LOGGER.debug("Business message {} from secretary owner {} is ignored",
                    message.getMessageId(), ownerId);
            return;
        }
        trackBusinessOwner(context, sender);
        SecretaryConfiguration configuration = context.managers().secretaryConfigurations().resolve(ownerId);
        SecretaryContact contact = context.managers().secretaryContacts().getSecretaryContact(connectionId, sender.getId()).orElse(null);

        if (contact != null && contact.status() == SecretaryContactStatus.ALLOWED)
        {
            LOGGER.debug("Business message {} from approved contact {} in connection {} is ignored", message.getMessageId(), sender.getId(), connectionId);
            return;
        }

        if (contact != null && contact.status() == SecretaryContactStatus.DENIED)
        {
            deleteBusinessMessage(context, message);
            return;
        }

        // The contact is absent or still UNKNOWN, so it needs to be analyzed. Only the first thread
        // to claim the contact may register, analyze, and decide for it; concurrent duplicates from
        // a catch-up batch are skipped so exactly one decision (and owner notification) is emitted.
        if (!claimContact(context, connectionId, sender.getId()))
        {
            LOGGER.debug("Business message {} from {} in connection {} skipped: contact claim held by another thread", message.getMessageId(), sender.getId(), connectionId);
            return;
        }

        if (contact == null)
        {
            LOGGER.info("Registered new secretary contact {} in connection {}", sender.getId(), connectionId);
            registerSecretaryContact(context, message, sender);
        }

        Recommendation recommendation = recommend(context, configuration, message, sender);
        if (!recommendation.analyzed())
        {
            // No analysis was possible (federation offline, textless message in privacy mode, ...),
            // so the contact stays unknown. Release the claim so a later message can retry it.
            releaseContactClaim(context, connectionId, sender.getId());
            LOGGER.debug("Business message {} from {} in connection {} passes through; contact stays unknown", message.getMessageId(), sender.getId(), connectionId);
            return;
        }

        boolean allowed = recommendation.suggestion() == null;
        if (configuration.behavior() == ScanningBehavior.STRICT)
        {
            if (allowed)
            {
                if (!setContactStatus(context, connectionId, sender.getId(), SecretaryContactStatus.ALLOWED))
                {
                    releaseContactClaim(context, connectionId, sender.getId());
                    return;
                }
                notifyAllowedOwner(context, ownerId, message);
            }
            else
            {
                boolean deleted = deleteBusinessMessage(context, message);
                if (!setContactStatus(context, connectionId, sender.getId(), SecretaryContactStatus.DENIED))
                {
                    releaseContactClaim(context, connectionId, sender.getId());
                    return;
                }
                notifyOwner(context, ownerId, message, recommendation.suggestion(), deleted, SecretaryContactStatus.DENIED);
            }
            return;
        }

        // PASSIVE: automatically allow first-contact messages, notifying the owner of every
        // decision so they can review and change it. Flagged messages are kept in the chat;
        // the owner is notified with the Federation suggestion.
        if (!setContactStatus(context, connectionId, sender.getId(), SecretaryContactStatus.ALLOWED))
        {
            releaseContactClaim(context, connectionId, sender.getId());
            return;
        }
        if (allowed)
        {
            notifyAllowedOwner(context, ownerId, message);
        }
        else
        {
            notifyOwner(context, ownerId, message, recommendation.suggestion(), false, SecretaryContactStatus.ALLOWED);
        }
    }

    /**
     * Analyzes a business message and its sender to determine if any action is suggested.
     * Leverages Federation services to assess the content and sender, and indicates
     * whether the message requires action based on the analysis results.
     *
     * @param context the update services and configuration for processing the message
     * @param configuration the secretary configuration containing behavior settings
     * @param message the business message to be analyzed
     * @param sender the user who sent the message
     * @return a {@code Recommendation} indicating the suggested action or analysis results;
     *         returns {@code Recommendation.UNKNOWN} if Federation services were unavailable or both
     *         content and entity analyses failed
     */
    static Recommendation recommend(HandlerContext context, SecretaryConfiguration configuration, Message message, User sender)
    {
        FederationService federation = context.federation();
        if (!federation.isAvailable())
        {
            return Recommendation.UNKNOWN;
        }

        String text = MessageContent.textOrCaption(message);
        boolean hasText = text != null && !text.isBlank();
        String authorEntity = sender.getId() + "@telegram.org";
        boolean contentAnalyzed = false;
        boolean entityAnalyzed = false;
        SuggestedAction contentSuggestion = null;
        SuggestedAction entitySuggestion = null;

        if (hasText)
        {
            try
            {
                ScannedContent scannedContent = federation.scanContent(MessageContent.buildScanInput(message, false), authorEntity);
                contentAnalyzed = true;
                contentSuggestion = scannedContent.suggestedAction();
            }
            catch (FederationException e)
            {
                LOGGER.warn("Failed to scan business message {} from {} in connection {}: {}",
                        message.getMessageId(), sender.getId(), message.getBusinessConnectionId(), e.getMessage());
            }
        }
        if (!hasText || configuration.behavior() == ScanningBehavior.STRICT)
        {
            try
            {
                EntityQueryResult query = federation.queryEntity(authorEntity).orElse(null);
                if (query != null)
                {
                    entityAnalyzed = true;
                    entitySuggestion = query.suggestedAction();
                }
            }
            catch (FederationException e)
            {
                LOGGER.warn("Failed to query business sender {} in connection {}: {}", sender.getId(), message.getBusinessConnectionId(), e.getMessage());
            }
        }
        if (!contentAnalyzed && !entityAnalyzed)
        {
            return Recommendation.UNKNOWN;
        }
        SuggestedAction suggestion = moreSevere(contentSuggestion, entitySuggestion);
        return suggestion == null ? Recommendation.CLEAN : new Recommendation(suggestion);
    }

    /**
     * Determines the more severe of two suggested actions based on their ranking.
     * If either suggested action is null, the other action is returned.
     * If both actions are non-null, the action with the higher severity (as determined
     * by its rank) is returned.
     *
     * @param first the first suggested action to compare, may be null
     * @param second the second suggested action to compare, may be null
     * @return the suggested action that is more severe, or the non-null action if one is null
     */
    private static SuggestedAction moreSevere(SuggestedAction first, SuggestedAction second)
    {
        if (first == null)
        {
            return second;
        }
        if (second == null)
        {
            return first;
        }
        return actionRank(first) >= actionRank(second) ? first : second;
    }

    /**
     * Returns the rank of the specified suggested action. The rank reflects the severity or priority
     * of the action, where lower numerical values correspond to less severe actions and higher values
     * indicate more severe actions.
     *
     * @param action the suggested action to rank; must be a non-null value of the SuggestedAction enum
     * @return an integer representing the rank of the given action, with higher values indicating more
     *         severe actions. The possible return values are:
     *         1 - CAUTION
     *         2 - BLOCK_CONTENT
     *         3 - TEMPORARILY_BLOCK_ENTITY
     *         4 - PERMANENTLY_BLOCK_ENTITY
     */
    private static int actionRank(SuggestedAction action)
    {
        return switch (action)
        {
            case CAUTION -> 1;
            case BLOCK_CONTENT -> 2;
            case TEMPORARILY_BLOCK_ENTITY -> 3;
            case PERMANENTLY_BLOCK_ENTITY -> 4;
        };
    }

    /**
     * Notifies the owner when a contact's message has been allowed.
     * This method sends a formatted notification message to the owner's chat ID,
     * if the Telegram client is available within the provided handler context.
     *
     * @param context the handler context containing services and configurations
     * @param ownerId the unique identifier of the owner to notify
     * @param message the business message related to the allowed contact
     */
    private static void notifyAllowedOwner(HandlerContext context, long ownerId, Message message)
    {
        if (context.telegramClient() == null)
        {
            return;
        }
        Language lang = context.managers().languagePreferences().getUserLanguage(ownerId);
        String html = allowedNotificationHtml(context.languages(), lang, message);
        tryExecute(context, "secretary-contact-allowed", SendMessage.builder()
                .chatId(String.valueOf(ownerId))
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(contactDecisionMarkup(context.languages(),
                        new ContactDecision(message.getBusinessConnectionId(), message.getFrom().getId(), lang,
                                SecretaryContactStatus.ALLOWED)))
                .build());
    }

    /**
     * Generates an HTML-formatted notification message indicating that a notification
     * is allowed for the specified user and message content.
     * <p>
     * This method relies on the provided language manager to fetch localized message templates
     * for the notification title, contact mention, and notification body. The sender of the message
     * is mentioned within the notification content.
     *
     * @param languageManager the language manager used to retrieve translations for the notification
     * @param lang the language to be used when formatting the notification
     * @param message the source message that initiated the notification
     * @return a concatenated HTML string representing the localized notification content
     */
    static String allowedNotificationHtml(LanguageManager languageManager, Language lang,
                                           Message message)
    {
        return languageManager.get(lang, "secretary", "notification_allowed_title")
                + languageManager.get(lang, "secretary", "notification_contact",
                NotificationFormatter.userMention(message.getFrom(), lang))
                + languageManager.get(lang, "secretary", "notification_allowed_body");
    }

    /**
     * Notifies the owner of a flagged or noteworthy message, providing contextual information
     * and a suggested action.
     *
     * @param context The handler context that provides access to required services and configurations.
     * @param ownerId The unique identifier of the message owner's Telegram account.
     * @param message The message object containing the content and metadata to be reviewed.
     * @param suggestion The suggested action (e.g., allow, deny) based on the content review.
     * @param deleted A flag indicating whether the referenced message has been deleted.
     * @param contactStatus The status of the contact associated with the message (e.g., unknown, allowed, denied).
     */
    private static void notifyOwner(HandlerContext context, long ownerId, Message message, SuggestedAction suggestion, boolean deleted, SecretaryContactStatus contactStatus)
    {
        if (context.telegramClient() == null)
        {
            return;
        }
        Language lang = context.managers().languagePreferences().getUserLanguage(ownerId);
        Integer evidenceId = echoFlaggedContent(context, ownerId, message, lang);
        String html = notificationHtml(context.languages(), lang, suggestion, message, deleted, evidenceId == null);
        var builder = SendMessage.builder()
                .chatId(String.valueOf(ownerId))
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(contactDecisionMarkup(context.languages(),
                        new ContactDecision(message.getBusinessConnectionId(), message.getFrom().getId(), lang, contactStatus)));
        if (evidenceId != null)
        {
            builder.replyToMessageId(evidenceId);
        }
        tryExecute(context, "secretary-notification", builder.build());
    }

    /**
     * Processes and echoes flagged content from a given message by analyzing its attachments
     * or text, and returns an identifier for the echoed item if applicable.
     *
     * @param context the execution context providing services and configuration
     * @param ownerId the unique identifier of the owner processing the message
     * @param message the message containing the flagged content to be echoed
     * @param lang the language for processing and formatting message content
     * @return an identifier for the echoed content if successful, or null if no content was echoed
     */
    private static Integer echoFlaggedContent(HandlerContext context, long ownerId, Message message, Language lang)
    {
        AttachmentInfo attachment = attachmentInfo(message);
        if (attachment != null)
        {
            Integer echoed = echoAttachment(context, ownerId, message, attachment, lang);
            if (echoed != null)
            {
                return echoed;
            }
            return echoAttachmentDetails(context, ownerId, message, attachment, lang);
        }
        return echoTextContent(context, ownerId, message, lang);
    }

    /**
     * Echoes the textual content of a message to a specific owner. If the message contains
     * no text or caption, the method returns null without performing any action.
     *
     * @param context the context containing services and configuration, including the Telegram client
     * @param ownerId the unique identifier for the owner to whom the message will be echoed
     * @param message the original message containing the text or caption to be echoed
     * @param lang the language configuration to format the echoed message appropriately
     * @return the message identifier of the echoed message, or null if the input message had no text or caption
     */
    private static Integer echoTextContent(HandlerContext context, long ownerId, Message message, Language lang)
    {
        OkHttpTelegramClient client = context.telegramClient();
        String content = MessageContent.textOrCaption(message);
        if (content == null || content.isBlank())
        {
            return null;
        }
        SendMessage echo = SendMessage.builder()
                .chatId(String.valueOf(ownerId))
                .text(context.languages().get(lang, "secretary", "notification_evidence",
                        NotificationFormatter.userMention(message.getFrom(), lang),
                        NotificationFormatter.escapeAndTruncate(content, NotificationFormatter.MAX_DETAIL_LENGTH, lang)))
                .parseMode(ParseMode.HTML)
                .build();
        return executeEcho(context, echo, () -> client.execute(echo));
    }

    /**
     * Echoes an attachment from a message to a specified chat.
     * Depending on the type of attachment (photo, video, animation, audio, voice, video note, sticker, or document),
     * it constructs the appropriate request and sends it to the Telegram bot API.
     *
     * @param context the handler context containing necessary services and configurations
     * @param ownerId the ID of the owner to which the attachment should be sent
     * @param message the original message containing the attachment
     * @param attachment the information about the attachment to be echoed
     * @param lang the language context for localized content, if applicable
     * @return the message ID of the sent message, or null if sending fails
     */
    private static Integer echoAttachment(HandlerContext context, long ownerId, Message message, AttachmentInfo attachment, Language lang)
    {
        OkHttpTelegramClient client = context.telegramClient();
        String chatId = String.valueOf(ownerId);
        String caption = evidenceCaption(message, lang);
        InputFile file = new InputFile(attachment.fileId());

        if (message.hasPhoto())
        {
            var request = SendPhoto.builder().chatId(chatId).photo(file).parseMode(ParseMode.HTML);
            if (caption != null)
            {
                request.caption(caption);
            }
            SendPhoto echo = request.build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasVideo())
        {
            var request = SendVideo.builder().chatId(chatId).video(file).parseMode(ParseMode.HTML);
            if (caption != null)
            {
                request.caption(caption);
            }
            SendVideo echo = request.build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasAnimation())
        {
            var request = SendAnimation.builder().chatId(chatId).animation(file).parseMode(ParseMode.HTML);
            if (caption != null)
            {
                request.caption(caption);
            }
            SendAnimation echo = request.build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasAudio())
        {
            var request = SendAudio.builder().chatId(chatId).audio(file).parseMode(ParseMode.HTML);
            if (caption != null)
            {
                request.caption(caption);
            }
            SendAudio echo = request.build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasVoice())
        {
            var request = SendVoice.builder().chatId(chatId).voice(file).parseMode(ParseMode.HTML);
            if (caption != null)
            {
                request.caption(caption);
            }
            SendVoice echo = request.build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasVideoNote())
        {
            SendVideoNote echo = SendVideoNote.builder().chatId(chatId).videoNote(file).build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        if (message.hasSticker())
        {
            SendSticker echo = SendSticker.builder().chatId(chatId).sticker(file).build();
            return executeEcho(context, echo, () -> client.execute(echo));
        }

        var request = SendDocument.builder().chatId(chatId).document(file).parseMode(ParseMode.HTML);
        if (caption != null)
        {
            request.caption(caption);
        }
        SendDocument echo = request.build();

        return executeEcho(context, echo, () -> client.execute(echo));
    }

    /**
     * Sends an echo message containing the details of the specified attachment to the owner via Telegram.
     *
     * @param context the context providing access to the update services, including the Telegram client
     * @param ownerId the unique identifier of the owner who will receive the echo message
     * @param message the original Telegram message containing the attachment
     * @param attachment the information about the attachment whose details will be echoed
     * @param lang the language used for generating the text of the echo message
     * @return an integer representing the response code of the Telegram API after attempting to send the echo message
     */
    private static Integer echoAttachmentDetails(HandlerContext context, long ownerId, Message message, AttachmentInfo attachment, Language lang)
    {
        OkHttpTelegramClient client = context.telegramClient();
        SendMessage echo = SendMessage.builder()
                .chatId(String.valueOf(ownerId))
                .text(evidenceDetailsHtml(context.languages(), lang, message, attachment))
                .parseMode(ParseMode.HTML)
                .build();
        return executeEcho(context, echo, () -> client.execute(echo));
    }

    /**
     * Executes the echo operation by logging the request, invoking the provided EchoCall,
     * and returning the associated message ID if successful.
     *
     * @param context the execution context containing update services and configuration
     * @param request the object representing the request data for the echo operation
     * @param call the EchoCall instance responsible for sending the echo request
     * @return the message ID returned by the EchoCall if successful, or null if an exception occurs
     */
    private static Integer executeEcho(HandlerContext context, Object request, EchoCall call)
    {
        MessageHelper.logOutgoing(context, "secretary-evidence", request);
        try
        {
            return call.send().getMessageId();
        }
        catch (TelegramApiException e)
        {
            MessageHelper.logFailed(context, "secretary-evidence", request, e);
            return null;
        }
    }

    /**
     * Generates and returns an evidence caption for a given message, formatted and truncated
     * according to the specified language.
     *
     * @param message the message from which the caption is extracted
     * @param lang the language to be used for truncation and formatting
     * @return the formatted and truncated caption if it exists and is not blank; otherwise, {@code null}
     */
    static String evidenceCaption(Message message, Language lang)
    {
        String caption = message.getCaption();
        if (caption == null || caption.isBlank())
        {
            return null;
        }

        return NotificationFormatter.escapeAndTruncate(caption, MAX_EVIDENCE_CAPTION_LENGTH, lang);
    }

    /**
     * Constructs an HTML fragment describing the details of an evidence attachment, including type, name, size, and caption.
     *
     * @param languageManager an instance of {@code LanguageManager} used for retrieving localized string resources
     * @param lang the {@code Language} specifying the localization language to be used
     * @param message the {@code Message} containing the evidence attachment details
     * @param attachment an {@code AttachmentInfo} object representing the evidence attachment metadata
     * @return an HTML string containing the localized details of the attachment with proper formatting
     */
    static String evidenceDetailsHtml(LanguageManager languageManager, Language lang, Message message, AttachmentInfo attachment)
    {
        StringBuilder html = new StringBuilder(languageManager.get(lang, "secretary", "notification_evidence_file", NotificationFormatter.userMention(message.getFrom(), lang)));
        String type = attachment.mimeType() != null && !attachment.mimeType().isBlank()
                ? NotificationFormatter.escapeAndTruncate(attachment.mimeType(), 128, lang)
                : languageManager.get(lang, "secretary", attachment.typeKey());
        html.append(languageManager.get(lang, "secretary", "evidence_file_type", type));
        if (attachment.fileName() != null && !attachment.fileName().isBlank())
        {
            html.append(languageManager.get(lang, "secretary", "evidence_file_name", NotificationFormatter.escapeAndTruncate(attachment.fileName(), 256, lang)));
        }
        if (attachment.fileSize() != null)
        {
            html.append(languageManager.get(lang, "secretary", "evidence_file_size", humanSize(attachment.fileSize())));
        }
        String caption = evidenceCaption(message, lang);
        if (caption != null)
        {
            html.append(languageManager.get(lang, "secretary", "evidence_file_caption", caption));
        }
        return html.toString();
    }

    /**
     * Extracts and returns attachment details from the provided message, if one is present.
     * The method analyzes the type of attachment (e.g., document, video, audio, photo)
     * and constructs an {@code AttachmentInfo} object with relevant properties.
     *
     * @param message the message object to extract attachment details from
     * @return an {@code AttachmentInfo} instance containing the extracted attachment details,
     *         or {@code null} if the message does not contain any attachment
     */
    static AttachmentInfo attachmentInfo(Message message)
    {
        if (message.hasDocument())
        {
            var document = message.getDocument();
            return new AttachmentInfo(document.getFileId(), "evidence_type_document", document.getFileName(), document.getMimeType(), document.getFileSize());
        }
        if (message.hasVideo())
        {
            var video = message.getVideo();
            return new AttachmentInfo(video.getFileId(), "evidence_type_video", video.getFileName(), video.getMimeType(), video.getFileSize());
        }
        if (message.hasAnimation())
        {
            var animation = message.getAnimation();
            return new AttachmentInfo(animation.getFileId(), "evidence_type_animation", animation.getFileName(), animation.getMimeType(), animation.getFileSize());
        }
        if (message.hasAudio())
        {
            var audio = message.getAudio();
            return new AttachmentInfo(audio.getFileId(), "evidence_type_audio", audio.getFileName(), audio.getMimeType(), audio.getFileSize());
        }
        if (message.hasVoice())
        {
            var voice = message.getVoice();
            return new AttachmentInfo(voice.getFileId(), "evidence_type_voice", null, voice.getMimeType(), voice.getFileSize());
        }
        if (message.hasVideoNote())
        {
            var videoNote = message.getVideoNote();
            return new AttachmentInfo(videoNote.getFileId(), "evidence_type_video_note", null, null, videoNote.getFileSize() == null ? null : videoNote.getFileSize().longValue());
        }
        if (message.hasSticker())
        {
            var sticker = message.getSticker();
            return new AttachmentInfo(sticker.getFileId(), "evidence_type_sticker", null, null, sticker.getFileSize() == null ? null : sticker.getFileSize().longValue());
        }
        if (message.hasPhoto() && message.getPhoto() != null && !message.getPhoto().isEmpty())
        {
            PhotoSize largest = message.getPhoto().get(message.getPhoto().size() - 1);
            return new AttachmentInfo(largest.getFileId(), "evidence_type_photo",
                    null, null, largest.getFileSize() == null ? null : largest.getFileSize().longValue());
        }
        return null;
    }

    /**
     * Converts a file size in bytes to a human-readable format, using units such as B, KB, MB, GB, or TB.
     *
     * @param bytes the file size in bytes to be converted
     * @return the human-readable representation of the file size, rounded to one decimal place, with the appropriate unit
     */
    private static String humanSize(long bytes)
    {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1_024 && unit < units.length - 1)
        {
            value /= 1_024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /**
     * Generates an HTML representation of a notification with the provided details.
     *
     * @param languageManager the language manager used to fetch localized text
     * @param lang the language in which the notification should be generated
     * @param suggestion the suggested action to include in the notification
     * @param message the original message triggering the notification
     * @param deleted a boolean indicating if the message has been marked as deleted
     * @param embedContent a boolean specifying whether to include the message content in the notification
     * @return a string containing the HTML representation of the notification
     */
    static String notificationHtml(LanguageManager languageManager, Language lang, SuggestedAction suggestion, Message message, boolean deleted, boolean embedContent)
    {
        StringBuilder html = new StringBuilder(languageManager.get(lang, "secretary", deleted ? "notification_deleted_title" : "notification_flagged_title"));
        html.append(languageManager.get(lang, "secretary", "notification_offender", NotificationFormatter.userMention(message.getFrom(), lang)));
        String content = embedContent ? MessageContent.textOrCaption(message) : null;
        if (content != null && !content.isBlank())
        {
            html.append(languageManager.get(lang, "secretary", "notification_content",
                    NotificationFormatter.escapeAndTruncate(content, NotificationFormatter.MAX_DETAIL_LENGTH, lang)));
        }
        html.append(languageManager.get(lang, "secretary", "notification_suggestion", MessageHelper.displayName(suggestion)));
        html.append(languageManager.get(lang, "secretary", deleted ? "notification_action_deleted" : "notification_action_notified"));
        return html.toString();
    }

    /**
     * Generates an inline keyboard markup for making a decision about a contact.
     *
     * @param languageManager the language manager used for localizing the labels on the buttons
     * @param decision the contact decision instance containing the decision and metadata
     * @return an InlineKeyboardMarkup object containing the buttons for the decision
     */
    static InlineKeyboardMarkup contactDecisionMarkup(LanguageManager languageManager, ContactDecision decision)
    {
        return contactDecisionMarkup(languageManager, decision, SecretarySettingsHandler.CONTACT_CALLBACK_PREFIX, false);
    }

    /**
     * Generates an inline keyboard markup for deciding actions on contact settings.
     *
     * @param languageManager the manager providing language-specific messages and resources
     * @param connectionId the identifier of the connection related to the contact
     * @param contactId the unique identifier for the contact being managed
     * @param lang the language to be used for the generated markup
     * @param contactStatus the current status of the contact in the secretary system
     * @return an InlineKeyboardMarkup object representing the decision markup for contact settings
     */
    static InlineKeyboardMarkup contactSettingsDecisionMarkup(LanguageManager languageManager, String connectionId, long contactId, Language lang, SecretaryContactStatus contactStatus)
    {
        return contactDecisionMarkup(languageManager, new ContactDecision(connectionId, contactId, lang, contactStatus),
                SecretarySettingsHandler.CONTACT_SETTINGS_CALLBACK_PREFIX, true);
    }


    /**
     * Builds an inline keyboard markup decision interface for managing contact decisions.
     * <p>
     * This method generates a keyboard interface with buttons for allowing or denying
     * contact requests, depending on the current contact status. Optionally, it can
     * include an additional settings button.
     *
     * @param languageManager The language manager used for localizing button text.
     * @param decision The object containing contact decision details, including contact status
     *                 and associated identifiers.
     * @param callbackPrefix The prefix used in callback data for uniquely identifying actions.
     * @param includeSettingsButton A flag indicating whether to include a settings button in the markup.
     *
     * @return An {@code InlineKeyboardMarkup} containing buttons for the specified contact decision.
     */
    private static InlineKeyboardMarkup contactDecisionMarkup(LanguageManager languageManager, ContactDecision decision, String callbackPrefix, boolean includeSettingsButton)
    {
        List<InlineKeyboardButton> decisionButtons = new ArrayList<>(2);
        if (decision.contactStatus() != SecretaryContactStatus.ALLOWED)
        {
            decisionButtons.add(InlineKeyboardButton.builder()
                    .text(languageManager.get(decision.lang(), "buttons", "allow_contact"))
                    .callbackData(callbackPrefix + ":" + decision.connectionId() + ":" + decision.contactId() + ":allow")
                    .build());
        }
        if (decision.contactStatus() != SecretaryContactStatus.DENIED)
        {
            decisionButtons.add(InlineKeyboardButton.builder()
                    .text(languageManager.get(decision.lang(), "buttons", "deny_contact"))
                    .callbackData(callbackPrefix + ":" + decision.connectionId() + ":" + decision.contactId() + ":deny")
                    .build());
        }

        List<InlineKeyboardRow> rows = new ArrayList<>(2);
        rows.add(new InlineKeyboardRow(decisionButtons));

        if (includeSettingsButton)
        {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(languageManager.get(decision.lang(), "buttons", "secretary_settings"))
                            .callbackData(SecretarySettingsHandler.OPEN_CALLBACK).build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Deletes a specified business message using the Telegram client associated with the provided context.
     * This method attempts to remove the message from the business connection.
     *
     * @param context the handler context containing configuration and services, including the Telegram client
     * @param message the business message to be deleted, containing the necessary identifying information
     *
     * @return {@code true} if the message deletion was successful; {@code false} otherwise
     */
    static boolean deleteBusinessMessage(HandlerContext context, Message message)
    {
        OkHttpTelegramClient client = context.telegramClient();
        if (client == null)
        {
            return false;
        }
        DeleteBusinessMessages delete = DeleteBusinessMessages.builder()
                .businessConnectionId(message.getBusinessConnectionId())
                .messageIds(List.of(message.getMessageId()))
                .build();
        MessageHelper.logOutgoing(context, "secretary-delete", delete);
        try
        {
            client.execute(delete);
            return true;
        }
        catch (TelegramApiException e)
        {
            LOGGER.warn("Failed to delete business message {} in chat {}: {}",
                    message.getMessageId(), message.getChatId(), e.getMessage());
            return false;
        }
    }

    /**
     * Registers a secretary contact for the given message and sender in the specified context.
     * This method creates a new {@code SecretaryContact} instance and attempts to register it in the system.
     * If a database exception occurs during the registration, a warning is logged.
     *
     * @param context the handler context, providing access to managers and system configurations
     * @param message the message that contains the business connection ID
     * @param sender the user representing the sender of the message
     */
    private static void registerSecretaryContact(HandlerContext context, Message message, User sender)
    {
        try
        {
            context.managers().secretaryContacts().registerSecretaryContact(new SecretaryContact(
                    message.getBusinessConnectionId(), sender.getId(), SecretaryContactStatus.UNKNOWN,
                    System.currentTimeMillis() / 1_000));
        }
        catch (net.nosial.spb.exceptions.DatabaseException e)
        {
            LOGGER.warn("Failed to register secretary contact {} in connection {}: {}",
                    sender.getId(), message.getBusinessConnectionId(), e.getMessage());
        }
    }

    /**
     * Determines if the given message is a decision command (e.g., "/allow", "/deny").
     * <p>
     * A decision command is identified by its text content being trimmed, split by whitespace,
     * and starts with "/allow" (or "/allow@" for specific contexts) or "/deny" (or "/deny@" for specific contexts).
     *
     * @param message the {@code Message} object to evaluate, which may be null
     * @return {@code true} if the message text starts with a recognized decision command;
     *         {@code false} if the message is null, the text is null, or it doesn't match the criteria
     */
    private static boolean isDecisionCommand(Message message)
    {
        if (message == null || message.getText() == null)
        {
            return false;
        }
        String command = message.getText().trim().split("\\s+", 2)[0].toLowerCase();
        return command.equals("/allow") || command.startsWith("/allow@") || command.equals("/deny") || command.startsWith("/deny@");
    }

    /**
     * Updates the status of a specified contact within a given connection.
     * <p>
     * The method attempts to set the status of the contact to the status provided.
     * If the operation fails due to a database exception, it logs a warning
     * and returns false. Additional logging is done when the contact is denied.
     *
     * @param context the handler context providing access to services and managers
     * @param connectionId the unique identifier of the connection containing the contact
     * @param contactId the unique identifier of the contact whose status is being updated
     * @param status the new status to be applied to the contact
     * @return true if the status was successfully updated, false otherwise
     */
    private static boolean setContactStatus(HandlerContext context, String connectionId, long contactId, SecretaryContactStatus status)
    {
        try
        {
            context.managers().secretaryContacts().setSecretaryContactStatus(connectionId, contactId, status);
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to set secretary contact {} in connection {} to {}: {}",
                    contactId, connectionId, status, e.getMessage());
            return false;
        }
        if (status == SecretaryContactStatus.DENIED)
        {
            LOGGER.info("Contact {} in connection {} was denied; future messages are blocked", contactId, connectionId);
        }
        return true;
    }

    /**
     * Retrieves the identifier of the owner associated with the specified business connection.
     *
     * @param context the handler context providing access to managers and configurations
     * @param connectionId the unique identifier of the business connection
     * @return an {@code Optional} containing the owner's identifier if the connection is associated
     *         with an owner; otherwise, an empty {@code Optional}
     */
    private static Optional<Long> ownerForConnection(HandlerContext context, String connectionId)
    {
        return context.managers().secretaryConfigurations().userIdByBusinessConnection(connectionId);
    }

    /**
     * Attempts to claim a contact for processing, ensuring that no other process can claim it
     * concurrently. This method uses a cache mechanism to manage contact claims.
     *
     * @param context the handler context that provides the cache for managing claims
     * @param connectionId the identifier of the connection associated with the contact
     * @param contactId the unique identifier of the contact
     * @return {@code true} if the contact was successfully claimed; {@code false} if it was already claimed
     */
    private static boolean claimContact(HandlerContext context, String connectionId, long contactId)
    {
        String key = CONTACT_CLAIM_CACHE_PREFIX + connectionId + ':' + contactId;
        AtomicBoolean claim = (AtomicBoolean) context.cache().get(key, ignored -> new AtomicBoolean(true));
        return Objects.requireNonNull(claim).compareAndSet(true, false);
    }

    /**
     * Releases the claim on a specific contact within a given connection by removing it from the cache.
     *
     * @param context the handler context containing update services and configuration
     * @param connectionId the identifier of the connection associated with the contact claim
     * @param contactId the unique identifier of the contact whose claim is being released
     */
    private static void releaseContactClaim(HandlerContext context, String connectionId, long contactId)
    {
        context.cache().remove(CONTACT_CLAIM_CACHE_PREFIX + connectionId + ':' + contactId);
    }

    /**
     * Tracks and saves a business owner in the system using their user information.
     *
     * @param context the context that provides access to system managers and services
     * @param user the user whose information is used to track the business owner
     */
    static void trackBusinessOwner(HandlerContext context, User user)
    {
        try
        {
            context.managers().users().saveUser(new UserIdentity(user.getId(), user.getUserName(), user.getFirstName(), user.getLastName()));
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to track user {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Extracted details of a message attachment, used either to re-serve the file by its id or
     * to describe it when re-serving is not possible.
     */
    record AttachmentInfo(String fileId, String typeKey, String fileName, String mimeType, Long fileSize)
    {
    }

    /**
     * Compares the given suggestions and reports on whether the message is actionable.
     */
    record Recommendation(boolean analyzed, SuggestedAction suggestion)
    {
        /** Federation was not asked, so nothing is known either way. */
        public static final Recommendation UNKNOWN = new Recommendation(false, null);

        /** Federation looked and found no reason to act. */
        public static final Recommendation CLEAN = new Recommendation(true, null);

        /**
         * Records what Federation suggested doing about a message and its sender.
         *
         * @param suggestion the suggested action, which must not be null; use {@link #CLEAN} when
         *                   Federation had nothing to suggest
         */
        public Recommendation(SuggestedAction suggestion)
        {
            this(true, Objects.requireNonNull(suggestion, "suggestion must not be null"));
        }
    }

    /**
     * Represents a decision related to a contact within a specific connection context.
     * <p>
     * This record encapsulates details such as the unique connection identifier,
     * the contact's unique identifier, the language preference, and the current
     * status of the contact as determined by the secretary.
     * <p>
     * Immutable and concise in design, this class is particularly useful for transferring
     * structured data regarding contact decisions within a system.
     *
     * @param connectionId The unique identifier representing a specific connection.
     * @param contactId The unique identifier associated with a contact.
     * @param lang The language preference associated with the contact or decision.
     * @param contactStatus The current status of the contact as determined by the
     *                      secretary's evaluation or decision process.
     */
    record ContactDecision(String connectionId, long contactId, Language lang, SecretaryContactStatus contactStatus)
    {
    }
}
