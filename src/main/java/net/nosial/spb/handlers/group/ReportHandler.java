package net.nosial.spb.handlers.group;

import net.nosial.spb.utilities.FlatMetadata;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.sessions.FalsePositiveReportSessionManager;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.sessions.ReportSessionManager;
import net.nosial.spb.enums.ReportPage;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.classes.ReportSubmissionService;
import net.nosial.spb.objects.context.ReportContext;
import net.nosial.spb.utilities.ReportAttachments;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.IncidentTypes;
import net.nosial.spb.utilities.MessageContent;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteEphemeralMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOrigin;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChannel;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChat;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginHiddenUser;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the {@code /report} command and all report-related inline keyboard callbacks.
 *
 * <p>The command is available in group chats where the bot is enabled and reporting is enabled,
 * and privately when a user forwards a message to the bot. In groups it must be sent as a reply to
 * the message being reported. When invoked with arguments the report is submitted immediately;
 * when invoked without arguments a two-step dialog is opened so the reporter can choose an
 * incident type and optionally add a comment.
 *
 * <p>Callbacks carry the report session hash and an action: selecting an incident type, submitting
 * the report, or cancelling the dialog. The handler validates that the callback originated from
 * the session owner and edits the prompt accordingly. Both ephemeral group prompts and regular
 * private-chat prompts are supported. Moderation actions ({@code delete}/{@code mute}/{@code ban})
 * announced by report notifications, and the one-time report-false-positive action attached to
 * scanning notifications, are handled here as well.
 *
 * <p>Validation and dialog prompts in groups are sent as ephemeral messages visible only to the
 * reporter. Report submission summaries are sent privately to the reporter when possible, with
 * in-chat fallback.
 */
@UpdateHandler(value = {UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY}, callbackData = ReportHandler.CALLBACK_PREFIX + ":", priority = 50)
public final class ReportHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportHandler.class);

    // Package-private: the class-level @UpdateHandler annotation reads it.
    static final String CALLBACK_PREFIX = "report";

    // Package-private: the class-level @UpdateHandler annotation reads it.
    static final String ACTION_CALLBACK_PREFIX = "report_action";
    private static final String EVIDENCE_TAG_USER = "user_report";
    private static final String EVIDENCE_TAG_ADMIN = "admin_report";
    private static final String TELEGRAM_ENTITY_SUFFIX = "@telegram.org";

    /**
     * Returns the report dialogs currently open.
     *
     * @param context the per-update context
     * @return the report session manager
     */
    private static ReportSessionManager reportSessions(HandlerContext context)
    {
        return context.sessions().report();
    }

    /**
     * Returns the one-shot false-positive actions currently outstanding.
     *
     * @param context the per-update context
     * @return the false-positive session manager
     */
    private static FalsePositiveReportSessionManager falsePositiveSessions(HandlerContext context)
    {
        return context.sessions().falsePositive();
    }

    @Override
    public boolean accepts(HandlerContext context)
    {
        Update update = context.update();
        if (update.hasCallbackQuery())
        {
            return true;
        }

        // A message reaches this handler three ways: it invokes /report, it answers the comment
        // prompt of an open dialog, or it is a message forwarded into a private chat to report.
        // Only the first is a command, so the match cannot be expressed by the annotation alone.
        Message message = update.getMessage();
        if (message == null || message.getFrom() == null)
        {
            return false;
        }

        return isReplyToActiveReportPrompt(context, message) || "report".equals(context.commandName()) || isPrivateReportForward(message);
    }

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Update update = context.update();
        if (update.hasCallbackQuery())
        {
            handleCallback(context, update.getCallbackQuery());
            return;
        }

        Message message = update.getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        if (handleReplyToPrompt(context, message))
        {
            return;
        }

        if (isCommand(context.update(), "report"))
        {
            String[] args = MessageHelper.parseArguments(message.getText());
            if (args.length == 1 && MessageHelper.isUuid(args[0]))
            {
                handleReportLookup(context, message, args[0]);
                return;
            }
            handleReportCommand(context, message);
            return;
        }

        if (isPrivateReportForward(message))
        {
            handlePrivateForwardReport(context, message);
        }
    }

    /**
     * Handles the callback query received from a Telegram bot and performs appropriate actions
     * based on the data within the callback query. This includes actions like dismissing,
     * submitting reports, or handling type-specific selections.
     *
     * @param context The handler context containing necessary services and utilities for processing.
     * @param callbackQuery The incoming callback query object, which contains details such as user data
     *                      and input required for handling the callback.
     * @throws TelegramApiException If an error occurs while interacting with the Telegram API, such as
     *                              sending messages or editing inline messages.
     */
    private void handleCallback(HandlerContext context, CallbackQuery callbackQuery) throws TelegramApiException
    {
        String data = callbackQuery.getData();
        if (data == null || callbackQuery.getFrom() == null)
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

        if ("dismiss".equals(parts[1]))
        {
            handleDismiss(context, callbackQuery);
            return;
        }

        if (parts.length != 3)
        {
            answer(context, callbackQuery);
            return;
        }

        String hash = parts[1];
        String action = parts[2];

        Message message = requireMessage(callbackQuery);
        if (message == null)
        {
            answer(context, callbackQuery);
            return;
        }

        switch (action)
        {
            case "cancel" ->
            {
                ReportContext session = reportSessions(context).takeOwned(hash, callbackQuery.getFrom().getId());
                if (session == null)
                {
                    answer(context, callbackQuery);
                    editToReportExpired(context, callbackQuery);
                    return;
                }
                Language lang = resolveLanguage(context, callbackQuery);
                editToHtml(context, message, session,
                        context.languages().get(lang, "report", "report_cancelled"),
                        ReportSubmissionService.dismissMarkup(context.languages(), lang));
                answer(context, callbackQuery);
            }
            case "submit" ->
            {
                ReportContext session = reportSessions(context).takeOwned(hash, callbackQuery.getFrom().getId());
                if (session == null)
                {
                    answer(context, callbackQuery);
                    editToReportExpired(context, callbackQuery);
                    return;
                }
                ReportSubmissionService.submit(context, session, null);
                answer(context, callbackQuery);
            }
            default ->
            {
                if (action.startsWith("type:"))
                {
                    ReportContext session = reportSessions(context).find(hash);
                    if (session == null || session.reporterId() != callbackQuery.getFrom().getId())
                    {
                        answer(context, callbackQuery);
                        editToReportExpired(context, callbackQuery);
                        return;
                    }
                    handleTypeSelection(context, callbackQuery, session, message, action);
                }
                else
                {
                    answer(context, callbackQuery);
                }
            }
        }
    }

    /**
     * Handles a message that is a reply to an active report dialog prompt.
     *
     * @param context the per-update command context
     * @param message the incoming reply message
     * @return {@code true} when the reply was consumed as a report comment
     * @throws TelegramApiException if a response cannot be sent
     */
    private boolean handleReplyToPrompt(HandlerContext context, Message message) throws TelegramApiException
    {
        Message replyTo = message.getReplyToMessage();
        if (replyTo == null)
        {
            return false;
        }

        Integer promptMessageId = replyTo.getEphemeralMessageId();
        boolean ephemeral = promptMessageId != null;
        if (!ephemeral)
        {
            promptMessageId = replyTo.getMessageId();
        }
        if (promptMessageId == null)
        {
            return false;
        }

        ReportContext session = reportSessions(context).findByPromptMessageId(message.getChatId(), promptMessageId);
        if (session == null || session.reporterId() != message.getFrom().getId())
        {
            return false;
        }

        if (session.page() != ReportPage.COMMENT)
        {
            sendEphemeralOrRegular(context, message,
                    context.languages().get(resolveLanguage(context, message), "report", "dialog_no_longer_accepting"),
                    session.ephemeral());
            return true;
        }

        String comment = message.getText();
        if (comment == null || comment.isBlank())
        {
            sendEphemeralOrRegular(context, message,
                    context.languages().get(resolveLanguage(context, message), "report", "empty_comment"),
                    session.ephemeral());
            return true;
        }

        session = reportSessions(context).takeOwnedByPromptMessageId(message.getChatId(), promptMessageId, message.getFrom().getId());
        if (session == null)
        {
            return false;
        }

        ReportSubmissionService.submit(context, session, comment);
        deleteCommandMessage(context, message);
        return true;
    }

    /**
     * Handles a {@code /report} command invocation.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @throws TelegramApiException if a response cannot be sent
     */
    private void handleReportCommand(HandlerContext context, Message message) throws TelegramApiException
    {
        if (!isGroupChat(message))
        {
            return;
        }

        ChatConfiguration configuration = context.managers().chatConfigurations().resolve(message.getChatId());
        if (!configuration.enabled() || !configuration.reportingEnabled())
        {
            return;
        }

        if (!context.federation().isAvailable())
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "federation_not_configured"), true);
            return;
        }

        // /report always submits as the bot itself, which the server refuses unconditionally
        // without client permissions, regardless of what it allows anonymously.
        if (!context.federation().isAuthenticated())
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "federation_not_authorized"), true);
            return;
        }

        if (!message.isReply() || message.getReplyToMessage() == null)
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "usage"), true);
            return;
        }

        Message targetMessage = message.getReplyToMessage();
        if (MessageHelper.isReplyToTopicHeader(message))
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "usage"), true);
            return;
        }
        User targetAuthor = targetMessage.getFrom();
        if (targetAuthor == null)
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "cannot_be_reported"), true);
            return;
        }
        if (targetAuthor.getId().longValue() == message.getFrom().getId().longValue())
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "self_report"), true);
            return;
        }
        if (isChatAdministrator(context, message.getChatId(), targetAuthor.getId().longValue()))
        {
            sendText(context, message, context.languages().get(resolveLanguage(context, message), "report", "admin_report"), true);
            return;
        }

        String[] args = MessageHelper.parseArguments(message.getText());
        if (args.length == 0)
        {
            startDialog(context, message, targetMessage, true);
        }
        else
        {
            // The first argument names the incident type when it is one; otherwise the whole
            // argument list is the reporter's comment and the report is filed as spam.
            IncidentType incidentType = IncidentTypes.parse(args[0]);
            String comment = incidentType != null
                    ? (args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null)
                    : String.join(" ", args);
            submitDirectReport(context, message, targetMessage,
                    incidentType != null ? incidentType : IncidentType.SPAM, comment);
        }
    }

    /**
     * Handles a {@code /report <uuid>} command by querying the Federation server for the
     * given report and displaying its information.
     *
     * <p>In group chats the response is ephemeral so only the caller sees the result. In
     * private chats a regular message is sent.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param reportUuid the Federation report UUID to look up
     * @throws TelegramApiException if a response cannot be sent
     */
    private void handleReportLookup(HandlerContext context, Message message, String reportUuid)
            throws TelegramApiException
    {
        ReportRecord report;
        try
        {
            report = context.federation().report(reportUuid).orElse(null);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch report {}: {}", reportUuid, e.getMessage());
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "query_error"), false, null);
            return;
        }
        if (report == null)
        {
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "not_found", HtmlEscape.escape(reportUuid)), false, null);
            return;
        }

        List<EvidenceRecord> evidence;
        try
        {
            evidence = context.federation().reportEvidence(reportUuid, 10);
        }
        catch (FederationException e)
        {
            LOGGER.debug("Failed to fetch evidence for report {}: {}", reportUuid, e.getMessage());
            evidence = List.of();
        }

        String html = buildReportInfoHtml(context.languages(), resolveLanguage(context, message), report, evidence);
        sendHtml(context, message, html, isGroupChat(message), null);
    }

    /**
     * Handles a forwarded message sent to the bot privately.
     *
     * @param context the per-update command context
     * @param message the forwarded message
     * @throws TelegramApiException if a response cannot be sent
     */
    private void handlePrivateForwardReport(HandlerContext context, Message message) throws TelegramApiException
    {
        if (!context.federation().isAvailable())
        {
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "federation_not_configured"), false, null);
            return;
        }

        // A forwarded message is reported as the bot itself, same as /report.
        if (!context.federation().isAuthenticated())
        {
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "federation_not_authorized"), false, null);
            return;
        }

        long reporterId = message.getFrom().getId();
        Long authorId = extractForwardAuthorId(message);
        if (authorId == null)
        {
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "hidden_author_forward"), false, null);
            return;
        }
        if (authorId == reporterId)
        {
            sendHtml(context, message, context.languages().get(resolveLanguage(context, message), "report", "self_report"), false, null);
            return;
        }

        long targetMessageId = extractForwardMessageId(message);
        boolean reporterIsAdmin = isChatAdministrator(context, message.getChatId(), message.getFrom().getId());
        ReportContext session = reportSessions(context).create(message, targetMessageId, authorId, ReportAttachments.forReport(context, message), reporterIsAdmin);

        InlineKeyboardMarkup markup = buildIncidentTypeMarkup(context.languages(), resolveLanguage(context, message), session);
        SendMessage prompt = SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .text(context.languages().get(resolveLanguage(context, message), "report", "incident_prompt"))
                .replyMarkup(markup)
                .build();

        Message sent = context.telegramClient().execute(prompt);
        if (sent != null)
        {
            reportSessions(context).updatePromptMessageId(session.hash(), sent.getMessageId());
        }
    }

    /**
     * Starts the two-step report dialog.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param targetMessage the message being reported
     * @param ephemeral whether the prompt should be ephemeral
     * @throws TelegramApiException if the prompt cannot be sent
     */
    private void startDialog(HandlerContext context, Message message, Message targetMessage, boolean ephemeral) throws TelegramApiException
    {
        Integer messageThreadId = targetMessage.getMessageThreadId();
        boolean reporterIsAdmin = isChatAdministrator(context, message.getChatId(), message.getFrom().getId());
        ReportContext session = reportSessions(context).create(message, targetMessage,
                ReportAttachments.forReport(context, targetMessage), reporterIsAdmin, ephemeral);

        InlineKeyboardMarkup markup = buildIncidentTypeMarkup(context.languages(),
                resolveLanguage(context, message), session);
        SendMessage prompt = SendMessage.builder()
                .chatId(String.valueOf(message.getChatId()))
                .messageThreadId(messageThreadId)
                .receiverUserId(ephemeral ? message.getFrom().getId() : null)
                .text(context.languages()
                        .get(resolveLanguage(context, message), "report", "incident_prompt"))
                .replyMarkup(markup)
                .build();

        Message sent = context.telegramClient().execute(prompt);
        if (sent != null)
        {
            Integer promptMessageId = ephemeral ? sent.getEphemeralMessageId() : sent.getMessageId();
            if (promptMessageId != null)
            {
                reportSessions(context).updatePromptMessageId(session.hash(), promptMessageId);
            }
        }
        deleteCommandMessage(context, message);
    }

    /**
     * Submits a report immediately from the command arguments.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param targetMessage the message being reported
     * @param incidentType the incident the report is filed under
     * @param comment the reporter's comment, or {@code null}
     * @throws TelegramApiException if the submission response cannot be sent
     */
    private void submitDirectReport(HandlerContext context, Message message, Message targetMessage,
                                    IncidentType incidentType, String comment) throws TelegramApiException
    {
        Integer messageThreadId = targetMessage.getMessageThreadId();
        boolean reporterIsAdmin = isChatAdministrator(context, message.getChatId(), message.getFrom().getId());
        ReportContext session = new ReportContext(null, message.getFrom().getId(), message.getChatId(),
                targetMessage.getMessageId(), targetMessage.getFrom().getId(), MessageContent.textOrCaption(targetMessage),
                ReportAttachments.forReport(context, targetMessage), FlatMetadata.of(targetMessage), null, true,
                messageThreadId, null, incidentType,
                reporterIsAdmin, System.currentTimeMillis(), System.currentTimeMillis());

        deleteCommandMessage(context, message);
        ReportSubmissionService.submit(context, session, comment);
    }

    /**
     * Returns whether the message is a reply to an active report dialog prompt.
     *
     * @param message the incoming message
     * @return {@code true} when the message replies to an active prompt
     */
    private static boolean isReplyToActiveReportPrompt(HandlerContext context, Message message)
    {
        Message replyTo = message.getReplyToMessage();
        if (replyTo == null)
        {
            return false;
        }

        Integer promptMessageId = replyTo.getEphemeralMessageId();
        if (promptMessageId == null)
        {
            promptMessageId = replyTo.getMessageId();
        }
        if (promptMessageId == null)
        {
            return false;
        }
        return reportSessions(context).findByPromptMessageId(message.getChatId(), promptMessageId) != null;
    }

    /**
     * Returns whether the message is a forwarded message sent privately to the bot, regardless of
     * whether the original author can be resolved.
     *
     * <p>Origin-hidden forwards and forwards of the reporter's own messages still qualify so that
     * {@link #handle(HandlerContext)} can explain why the report is not possible instead of
     * silently ignoring the message.
     *
     * @param message the incoming message
     * @return {@code true} when the message is a private forward
     */
    private static boolean isPrivateReportForward(Message message)
    {
        return isPrivateChat(message) && message.getFrom() != null && hasForwardOrigin(message);
    }

    /**
     * Returns whether the message carries any forward origin information.
     *
     * @param message the incoming message
     * @return {@code true} when the message is a forward
     */
    private static boolean hasForwardOrigin(Message message)
    {
        return message.getForwardOrigin() != null || message.getForwardFrom() != null || message.getForwardFromChat() != null;
    }

    /**
     * Extracts the original author id from a forwarded message, or {@code null} when the author is
     * hidden or the message is not a forward.
     *
     * @param message the forwarded message
     * @return the author id, or {@code null}
     */
    private static Long extractForwardAuthorId(Message message)
    {
        MessageOrigin origin = message.getForwardOrigin();

        if (origin instanceof MessageOriginUser userOrigin)
        {
            User sender = userOrigin.getSenderUser();
            return sender != null ? sender.getId() : null;
        }

        if (origin instanceof MessageOriginChannel channelOrigin)
        {
            Chat chat = channelOrigin.getChat();
            return chat != null ? chat.getId() : null;
        }

        if (origin instanceof MessageOriginChat chatOrigin)
        {
            Chat chat = chatOrigin.getSenderChat();
            return chat != null ? chat.getId() : null;
        }

        if (origin instanceof MessageOriginHiddenUser)
        {
            return null;
        }

        User forwardFrom = message.getForwardFrom();
        if (forwardFrom != null)
        {
            return forwardFrom.getId();
        }
        Chat forwardFromChat = message.getForwardFromChat();
        if (forwardFromChat != null)
        {
            return forwardFromChat.getId();
        }

        return null;
    }

    /**
     * Extracts the original message id from a forwarded message, falling back to the forwarded
     * message's own id when the original id is unavailable.
     *
     * @param message the forwarded message
     * @return the message id to record
     */
    private static long extractForwardMessageId(Message message)
    {
        MessageOrigin origin = message.getForwardOrigin();
        if (origin instanceof MessageOriginChannel channelOrigin && channelOrigin.getMessageId() != null)
        {
            return channelOrigin.getMessageId();
        }
        if (message.getForwardFromMessageId() != null)
        {
            return message.getForwardFromMessageId();
        }
        return message.getMessageId();
    }

    /**
     * Builds the inline keyboard for selecting an incident type. Each incident type is shown on its
     * own row using a human-readable label.
     *
     * @param session the report session
     * @return the inline keyboard markup
     */
    private static InlineKeyboardMarkup buildIncidentTypeMarkup(LanguageManager lm, Language lang, ReportContext session)
    {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (IncidentType type : IncidentType.values())
        {
            rows.add(new InlineKeyboardRow(button(MessageHelper.displayName(type), CALLBACK_PREFIX + ":" + session.hash() + ":type:" + type.name())));
        }
        rows.add(new InlineKeyboardRow(button(lm.get(lang, "general", "cancel"), CALLBACK_PREFIX + ":" + session.hash() + ":cancel")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Deletes the message carrying the Dismiss button.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @throws TelegramApiException if the delete cannot be sent
     */
    private static void handleDismiss(HandlerContext context, CallbackQuery callbackQuery) throws TelegramApiException
    {
        Message message = requireMessage(callbackQuery);
        if (message != null && callbackQuery.getFrom() != null)
        {
            try
            {
                if (message.getEphemeralMessageId() != null)
                {
                    context.telegramClient().execute(DeleteEphemeralMessage.builder()
                            .chatId(String.valueOf(message.getChatId()))
                            .receiverUserId(callbackQuery.getFrom().getId())
                            .ephemeralMessageId(message.getEphemeralMessageId())
                            .build());
                }
                else
                {
                    context.telegramClient().execute(DeleteMessage.builder()
                            .chatId(String.valueOf(message.getChatId()))
                            .messageId(message.getMessageId())
                            .build());
                }
            }
            catch (TelegramApiException e)
            {
                LOGGER.debug("Could not dismiss report summary in chat {}: {}",
                        message.getChatId(), e.getMessage());
            }
        }
        answer(context, callbackQuery);
    }

    

    

    /**
     * Applies the selected incident type and advances the dialog to the comment page.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @param session the validated report session
     * @param message the prompt message
     * @param action the callback action (e.g. {@code type:SPAM})
     * @throws TelegramApiException if the edit cannot be sent
     */
    private void handleTypeSelection(HandlerContext context, CallbackQuery callbackQuery, ReportContext session,
                                     Message message, String action) throws TelegramApiException
    {
        String typeName = action.substring("type:".length());
        IncidentType incidentType;
        try
        {
            incidentType = IncidentType.valueOf(typeName);
        }
        catch (IllegalArgumentException e)
        {
            LOGGER.warn("Unknown incident type '{}' in report callback", typeName);
            answer(context, callbackQuery);
            return;
        }

        session = reportSessions(context).updateIncidentType(session.hash(), incidentType);
        if (session == null)
        {
            answer(context, callbackQuery);
            editToReportExpired(context, callbackQuery);
            return;
        }

        Language lang = resolveLanguage(context, callbackQuery);
        InlineKeyboardMarkup markup = markup(
                new InlineKeyboardRow(
                        button(context.languages().get(lang, "buttons", "submit_report"),
                                CALLBACK_PREFIX + ":" + session.hash() + ":submit"),
                        button(context.languages().get(lang, "general", "cancel"),
                                CALLBACK_PREFIX + ":" + session.hash() + ":cancel")));

        editToText(context, message, session,
                context.languages().get(lang, "report", "comment_prompt"), markup);
        answer(context, callbackQuery);
    }

    /**
     * Edits the prompt to the given text and optional markup.
     *
     * @param context the per-update command context
     * @param message the prompt message
     * @param session the report session
     * @param text the new text
     * @param markup the new inline keyboard, or {@code null} to remove it
     * @throws TelegramApiException if the edit cannot be sent
     */
    private static void editToText(HandlerContext context, Message message, ReportContext session,
                                   String text, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        if (session.ephemeral())
        {
            if (message.getEphemeralMessageId() == null)
            {
                return;
            }
            context.telegramClient().execute(EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(session.chatId()))
                    .receiverUserId(session.reporterId())
                    .ephemeralMessageId(message.getEphemeralMessageId())
                    .text(text)
                    .replyMarkup(markup)
                    .build());
            return;
        }

        context.telegramClient().execute(EditMessageText.builder()
                .chatId(String.valueOf(session.chatId()))
                .messageId(message.getMessageId())
                .text(text)
                .replyMarkup(markup)
                .build());
    }

    /**
     * Edits the prompt to the given basic HTML text and optional markup.
     *
     * @param context the per-update command context
     * @param message the prompt message
     * @param session the report session
     * @param html the new HTML text
     * @param markup the new inline keyboard, or {@code null} to remove it
     * @throws TelegramApiException if the edit cannot be sent
     */
    private static void editToHtml(HandlerContext context, Message message, ReportContext session,
                                   String html, InlineKeyboardMarkup markup) throws TelegramApiException
    {
        if (session.ephemeral())
        {
            if (message.getEphemeralMessageId() == null)
            {
                return;
            }
            context.telegramClient().execute(EditEphemeralMessageText.builder()
                    .chatId(String.valueOf(session.chatId()))
                    .receiverUserId(session.reporterId())
                    .ephemeralMessageId(message.getEphemeralMessageId())
                    .text(html)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(markup)
                    .build());
            return;
        }

        context.telegramClient().execute(EditMessageText.builder()
                .chatId(String.valueOf(session.chatId()))
                .messageId(message.getMessageId())
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(markup)
                .build());
    }

    /**
     * Edits the prompt to indicate that the session has expired.
     *
     * @param context the per-update command context
     * @param callbackQuery the incoming callback query
     * @throws TelegramApiException if the edit cannot be sent
     */
    private static void editToReportExpired(HandlerContext context, CallbackQuery callbackQuery) throws TelegramApiException
    {
        Message message = requireMessage(callbackQuery);
        if (message == null || callbackQuery.getFrom() == null)
        {
            return;
        }

        Language lang = resolveLanguage(context, callbackQuery);
        String text = context.languages().get(lang, "report", "expired");
        InlineKeyboardMarkup markup = ReportSubmissionService.dismissMarkup(context.languages(), lang);
        editMessage(context, message, callbackQuery, text, markup);
    }

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    /**
     * Builds the HTML summary of a Federation report record and its associated evidence UUIDs.
     *
     * @param report the report record
     * @param evidence the evidence records associated with the report
     * @return the formatted HTML text
     */
    private static String buildReportInfoHtml(LanguageManager lm, Language lang,
                                              ReportRecord report, List<EvidenceRecord> evidence)
    {
        StringBuilder html = new StringBuilder(lm.get(lang, "report", "header"));
        html.append(lm.get(lang, "report", "report_id", HtmlEscape.escape(report.uuid()))).append('\n');
        html.append(lm.get(lang, "report", "type", report.incidentType() != null
                ? MessageHelper.displayName(report.incidentType())
                : lm.get(lang, "general", "unknown"))).append('\n');
        html.append(lm.get(lang, "report", "status", lm.get(lang, "general",
                report.opened() ? "open" : "closed"))).append('\n');
        html.append(lm.get(lang, "report", "automated", lm.get(lang, "general",
                report.automated() ? "yes" : "no"))).append('\n');

        if (report.message() != null && !report.message().isBlank())
        {
            html.append(lm.get(lang, "report", "message", HtmlEscape.escape(report.message()))).append('\n');
        }

        html.append('\n');
        html.append(lm.get(lang, "report", "entity", HtmlEscape.escape(report.reportingEntity()))).append('\n');
        html.append(lm.get(lang, "report", "submitted_by", HtmlEscape.escape(report.submittingOperator()))).append('\n');
        html.append(lm.get(lang, "report", "assigned_to",
                report.assignedOperator() != null && !report.assignedOperator().isBlank()
                        ? "<code>" + HtmlEscape.escape(report.assignedOperator()) + "</code>"
                        : lm.get(lang, "report", "unassigned"))).append('\n');
        html.append(lm.get(lang, "report", "created", MessageHelper.formatTimestamp(report.created()))).append('\n');
        html.append(lm.get(lang, "report", "updated", MessageHelper.formatTimestamp(report.updated()))).append('\n');

        html.append('\n');
        html.append(lm.get(lang, "report", "evidence_header"));
        if (evidence.isEmpty())
        {
            html.append(lm.get(lang, "report", "evidence_none"));
        }
        else
        {
            for (EvidenceRecord record : evidence)
            {
                html.append("\u2022 <code>").append(HtmlEscape.escape(record.uuid())).append("</code>\n");
            }
        }

        return html.toString();
    }

    
}