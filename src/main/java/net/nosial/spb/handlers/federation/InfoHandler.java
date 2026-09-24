package net.nosial.spb.handlers.federation;


import net.nosial.spb.exceptions.ArgumentParseException;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.records.EntityRecord;
import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.utilities.EntityInfoRenderer;
import net.nosial.spb.utilities.EntityResolver;
import net.nosial.spb.utilities.HtmlEscape;
import net.nosial.spb.utilities.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.regex.Pattern;

/**
 * The {@code /info} command handler.
 *
 * <p>The command can be executed anywhere: in a private chat, in a group or supergroup, or in a
 * channel. It looks up a security entity in the Federation database and replies with a summary of
 * the entity record, its current Federation recommendation, active blacklists, and recent reports.
 *
 * <p>The target entity can be given directly as an argument, choosing the resolution method by its
 * shape:
 * <ul>
 *     <li>{@code /info <id>} — a bare numeric Telegram user id, resolved to the entity address
 *     {@code <id>@telegram.org},</li>
 *     <li>{@code /info <username>} — a Telegram username, resolved to the user id via the local
 *     {@code users} table and, as a fallback, the Telegram API, then searched as
 *     {@code <id>@telegram.org}. A {@code @username} mention in the command resolves through its
 *     mention entity, which works even when the client does not put the raw username in the text,</li>
 *     <li>{@code /info <address>} — a full entity address (e.g. {@code 123456@telegram.org})
 *     searched directly,</li>
 *     <li>{@code /info <sha256>} — the SHA-256 hex digest of the entity address,</li>
 *     <li>{@code /info <uuid>} — the entity UUID.</li>
 * </ul>
 *
 *<p>When the command includes an argument, that explicit target takes precedence. Otherwise, when
 * the command is sent as a reply to a message, the target entity is the author of the replied-to
 * message (its Telegram user id as the entity address). When the command is sent as-is, without a
 * reply and without an argument, the target entity is the caller themselves.
 *
 * <p>All lookups are best-effort: a missing entity, an unresolvable username, or a Federation
 * server error is reported to the user and never propagated.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "info")
public final class InfoHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(InfoHandler.class);
    private static final String TELEGRAM_ENTITY_SUFFIX = "@telegram.org";

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        LOGGER.debug("Processing /info from {}: text='{}', entities={}", message.getFrom().getId(), message.getText(), message.getEntities());

        LanguageManager lm = context.languages();
        Language lang = resolveLanguage(context, message);

        if (!context.federation().isAvailable())
        {
            sendHtml(context, message, lm.get(lang, "info", "unavailable_no_federation"), false, null);
            return;
        }

        String identifier;
        try
        {
            identifier = resolveIdentifier(context, message, lang);
        }
        catch (ArgumentParseException e)
        {
            sendHtml(context, message, lm.get(lang, "info", "unavailable_error",
                    HtmlEscape.escape(e.getMessage())), false, null);
            return;
        }

        if (identifier == null)
        {
            identifier = selfIdentifier(message);
        }

        EntityRecord entity = EntityInfoRenderer.fetchEntity(context, identifier);
        if (entity == null)
        {
            sendHtml(context, message, lm.get(lang, "info", "entity_not_found",
                    HtmlEscape.escape(identifier)), false, null);
            return;
        }

        EntityQueryResult entityQuery = EntityInfoRenderer.fetchEntityQuery(context, identifier);
        sendEntityInfo(context, message, identifier, entity, entityQuery);
    }

    /**
     * Resolves the target entity identifier from the incoming message. An explicit command
     * argument takes precedence over the implicit author of a replied-to message.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @return the resolved identifier, or {@code null} when the command has neither an argument nor a reply to a message with a known author
     */
    private static String resolveIdentifier(HandlerContext context, Message message, Language lang) throws ArgumentParseException
    {
        String argumentIdentifier = argumentIdentifier(context, message, lang);
        if (argumentIdentifier != null)
        {
            return argumentIdentifier;
        }
        return replyIdentifier(message);
    }

    /**
     * Resolves the caller themselves as the target entity.
     *
     * @param message the incoming command message
     * @return the caller's entity identifier
     */
    private static String selfIdentifier(Message message)
    {
        return message.getFrom().getId() + TELEGRAM_ENTITY_SUFFIX;
    }

    /**
     * Resolves the author of the replied-to message as the target entity.
     *
     * <p>A reply to the message's own forum-topic header is treated as no reply: such a message is
     * the topic header itself, not a specific target message a user chose to query, so it falls
     * through to the caller as the target.
     *
     * @param message the incoming command message
     * @return the author's entity identifier, or {@code null} when the command is not a reply to a
     *         message with a known author nor a reply to its thread header
     */
    private static String replyIdentifier(Message message)
    {
        Message replyTo = message.getReplyToMessage();
        if (replyTo == null || MessageHelper.isReplyToTopicHeader(message))
        {
            return null;
        }

        User author = replyTo.getFrom();
        if (author == null)
        {
            return null;
        }

        return author.getId() + TELEGRAM_ENTITY_SUFFIX;
    }

    /**
     * Resolves the target entity from the command argument, choosing the resolution method by the
     * argument's shape.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @return the resolved identifier, or {@code null} when the command has no argument
     */
    private static String argumentIdentifier(HandlerContext context, Message message, Language lang) throws ArgumentParseException
    {
        String mention = mentionIdentifier(context, message, lang);
        if (mention != null)
        {
            return mention;
        }

        String argument = singleArgument(message);
        if (argument == null)
        {
            return null;
        }

        if (UUID_PATTERN.matcher(argument).matches())
        {
            return argument;
        }

        if (SHA256_PATTERN.matcher(argument).matches())
        {
            return argument;
        }

        if (argument.startsWith("@"))
        {
            return usernameIdentifier(context, argument.substring(1), lang);
        }

        if (argument.contains("@"))
        {
            return argument;
        }

        if (NUMERIC_PATTERN.matcher(argument).matches())
        {
            return argument + TELEGRAM_ENTITY_SUFFIX;
        }

        return usernameIdentifier(context, argument, lang);
    }

    /**
     * Resolves the target from a Telegram mention entity in the command's argument region, so
     * {@code /info @username} resolves regardless of how the client rendered the mention text.
     *
     * <p>A {@code text_mention} entity carries the mentioned {@link User} directly, giving the user
     * id without any lookup. A plain {@code mention} entity covers the {@code @username} text, whose
     * exact spelling is taken from the message text using the entity's offset and length.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @return the resolved identifier, or {@code null} when the message carries no mention entity in the argument region
     */
    private static String mentionIdentifier(HandlerContext context, Message message, Language lang) throws ArgumentParseException
    {
        if (message.getEntities() == null || message.getText() == null)
        {
            return null;
        }

        String text = message.getText();
        int argumentStart = MessageHelper.firstWhitespace(text);

        if (argumentStart < 0)
        {
            return null;
        }

        for (MessageEntity entity : message.getEntities())
        {
            Integer offset = entity.getOffset();
            Integer length = entity.getLength();
            if (offset <= argumentStart)
            {
                continue;
            }
            String type = entity.getType();

            if ("text_mention".equals(type) && entity.getUser() != null)
            {
                User user = entity.getUser();
                return user.getId() + TELEGRAM_ENTITY_SUFFIX;
            }

            if ("mention".equals(type))
            {
                int end = offset + length;
                if (end <= text.length())
                {
                    String mention = text.substring(offset, end);
                    if (mention.startsWith("@"))
                    {
                        return usernameIdentifier(context, mention.substring(1), lang);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Resolves a Telegram username to the entity address {@code <id>@telegram.org}, first through
     * the local {@code users} table and then, as a fallback, through the Telegram API.
     *
     * @param context the per-update command context
     * @param username the raw username, with or without the leading '@'
     * @return the entity identifier, or an identifier carrying an error message when the username cannot be resolved
     */
    private static String usernameIdentifier(HandlerContext context, String username, Language lang) throws ArgumentParseException
    {
        LanguageManager lm = context.languages();
        String normalized = normalizeUsername(username);
        if (normalized == null)
        {
            throw new ArgumentParseException(lm.get(lang, "info", "invalid_username"));
        }

        Long userId = context.managers().users().getUserIdByUsername(normalized).orElse(null);
        if (userId == null)
        {
            userId = resolveUsernameFromTelegram(context, normalized);
        }
        if (userId == null)
        {
            throw new ArgumentParseException(lm.get(lang, "info", "username_not_resolved", normalized));
        }

        return userId + TELEGRAM_ENTITY_SUFFIX;
    }

    /**
     * Resolves a Telegram username to its user id through the Telegram API, when the bot can see
     * the user's chat. Returns {@code null} when the username cannot be resolved.
     *
     * @param context the per-update command context
     * @param username the normalized username without the leading '@'
     * @return the user id, or {@code null}
     */
    private static Long resolveUsernameFromTelegram(HandlerContext context, String username)
    {
        try
        {
            ChatFullInfo chat = context.telegramClient().execute(GetChat.builder().chatId("@" + username).build());
            return chat != null ? chat.getId() : null;
        }
        catch (TelegramApiException e)
        {
            LOGGER.debug("Failed to resolve username @{} through the Telegram API: {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * Sends the formatted entity summary: the entity record itself, the Federation recommendation,
     * its active blacklists, and most recent reports.
     *
     * @param context the per-update command context
     * @param message the incoming command message
     * @param display the identifier the user queried, shown as a reference in the reply
     * @param entity the fetched entity record
     * @param entityQuery the Federation query result, or {@code null} when unavailable
     * @throws TelegramApiException if the reply cannot be sent
     */
    private static void sendEntityInfo(HandlerContext context, Message message, String display, EntityRecord entity, EntityQueryResult entityQuery) throws TelegramApiException
    {
        Language lang = resolveLanguage(context, message);
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "info", "header"));
        html.append(lm.get(lang, "info", "queried", HtmlEscape.escape(display))).append('\n');
        boolean privacyMode = context.managers().chatConfigurations().getChatConfiguration(message.getChatId()).map(ChatConfiguration::privacyMode).orElse(false);

        EntityInfoRenderer.appendEntityFields(html, context, entity, privacyMode, lang);

        html.append(lm.get(lang, "info", "reputation", EntityInfoRenderer.formatReputation(entity.reputation(), lm, lang))).append('\n');
        html.append(lm.get(lang, "info", "whitelisted",
                lm.get(lang, "general", entity.whitelisted() ? "yes" : "no"))).append('\n');

        if (entity.relationshipType() != null || entity.relationshipEntity() != null)
        {
            StringBuilder relationship = new StringBuilder();
            if (entity.relationshipType() != null)
            {
                relationship.append(HtmlEscape.escape(MessageHelper.humanize(entity.relationshipType().name())));
            }
            if (entity.relationshipEntity() != null)
            {
                if (!relationship.isEmpty())
                {
                    relationship.append(' ');
                }
                relationship.append(EntityResolver.resolve(context.federation(), context.managers().users(), entity.relationshipEntity(), privacyMode));
            }
            html.append(lm.get(lang, "info", "relationship", relationship)).append('\n');
        }

        html.append(lm.get(lang, "info", "created", HtmlEscape.escape(MessageHelper.formatTimestamp(entity.created())))).append('\n');
        html.append(lm.get(lang, "info", "updated", HtmlEscape.escape(MessageHelper.formatTimestamp(entity.updated())))).append('\n');

        EntityInfoRenderer.appendEntityQuery(html, entityQuery, lm, lang);
        EntityInfoRenderer.appendBlacklists(context, html, entity.uuid(), lm, lang);
        EntityInfoRenderer.appendReports(context, html, entity.uuid(), lm, lang);

        sendHtml(context, message, html.toString(), false, null);
    }

    /**
     * Normalizes a Telegram username by stripping a leading '@' and converting to lower case.
     *
     * @param username the raw username
     * @return the normalized username, or {@code null} when empty
     */
    private static String normalizeUsername(String username)
    {
        if (username == null || username.isBlank())
        {
            return null;
        }
        String normalized = username.trim();
        if (normalized.startsWith("@"))
        {
            normalized = normalized.substring(1);
        }
        normalized = normalized.toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }
}
