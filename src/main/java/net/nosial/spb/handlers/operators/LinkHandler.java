package net.nosial.spb.handlers.operators;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.jfederation.enums.EntityRelationshipType;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code /link} command handler for authenticated Federation operators.
 *
 * <p>Usage: {@code /link <entity> <target> <type>} where {@code entity} and {@code target}
 * may be Telegram usernames, user ids, mentions, Federation UUIDs, hashes, or entity addresses,
 * and {@code type} is one of {@code alternative}, {@code proxy}, or {@code child}.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "link")
public final class LinkHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkHandler.class);
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

        Language lang = resolveLanguage(context, message);
        long userId = message.getFrom().getId();

        Optional<net.nosial.spb.objects.database.OperatorIdentity> identityOpt = context.managers().operators().getOperator(userId);
        if (identityOpt.isEmpty())
        {
            sendHtml(context, message, context.languages().get(lang, "link", "not_authenticated"), false, null);
            return;
        }

        String[] args = net.nosial.spb.utilities.MessageHelper.parseArguments(message.getText());
        if (args.length < 3)
        {
            sendHtml(context, message, context.languages().get(lang, "link", "usage"), false, null);
            return;
        }

        String entityInput = args[0];
        String targetInput = args[1];
        String typeInput = args[2].toLowerCase();

        String typeValue;
        switch (typeInput)
        {
            case "alternative" -> typeValue = "ALTERNATIVE";
            case "proxy" -> typeValue = "PROXY";
            case "child" -> typeValue = "CHILD";
            default ->
            {
                sendHtml(context, message, context.languages().get(lang, "link", "invalid_type"), false, null);
                return;
            }
        }

        if (!context.federation().isAvailable())
        {
            sendHtml(context, message, context.languages().get(lang, "link", "federation_not_configured"), false, null);
            return;
        }

        String entityUuid = resolveEntityUuid(context, entityInput);
        String targetUuid = resolveEntityUuid(context, targetInput);

        if (entityUuid == null || entityUuid.isBlank())
        {
            sendHtml(context, message, context.languages().get(lang, "link", "entity_not_found", HtmlEscape.escape(entityInput)), false, null);
            return;
        }
        if (targetUuid == null || targetUuid.isBlank())
        {
            sendHtml(context, message, context.languages().get(lang, "link", "target_not_found", HtmlEscape.escape(targetInput)), false, null);
            return;
        }

        try
        {
            EntityRelationshipType relationshipType;
            switch (typeValue)
            {
                case "ALTERNATIVE" -> relationshipType = EntityRelationshipType.ALTERNATIVE;
                case "PROXY" -> relationshipType = EntityRelationshipType.PROXY;
                case "CHILD" -> relationshipType = EntityRelationshipType.CHILD;
                default -> relationshipType = null;
            }

            context.federation().linkEntities(identityOpt.get().accessToken(), entityUuid, targetUuid,
                    relationshipType);
            sendHtml(context, message, context.languages().get(lang, "link", "linked",
                    HtmlEscape.escape(entityInput), HtmlEscape.escape(targetInput), typeValue.toLowerCase()), false, null);
        }
        catch (FederationException e)
        {
            LOGGER.warn("Failed to set entity relationship for operator {}: {}", userId, e.getMessage());
            sendHtml(context, message, context.languages().get(lang, "link", "failed", HtmlEscape.escape(e.getMessage())), false, null);
        }
    }

    /**
     * Resolves a user-supplied identifier to a Federation-recognizable entity identifier.
     *
     * <p>Identifiers recognized directly by the Federation server (UUIDs, SHA-256 hashes, and
     * entity addresses) are passed through unchanged. Telegram identifiers the bot can recognize
     * on its own — user ids, {@code @username} mentions, and plain usernames — are resolved to
     * the {@code <id>@telegram.org} entity address.
     *
     * @param context the per-update command context
     * @param input the raw identifier supplied by the operator
     * @return the Federation entity identifier, or {@code null} when the input cannot be resolved
     */
    private static String resolveEntityUuid(HandlerContext context, String input)
    {
        if (input == null || input.isBlank())
        {
            return null;
        }
        String trimmed = input.trim();

        if (UUID_PATTERN.matcher(trimmed).matches())
        {
            return trimmed;
        }
        if (SHA256_PATTERN.matcher(trimmed).matches())
        {
            return trimmed;
        }
        if (trimmed.startsWith("@"))
        {
            return resolveUsername(context, trimmed.substring(1));
        }
        if (trimmed.contains("@"))
        {
            return trimmed;
        }
        if (trimmed.contains("/") || trimmed.contains(":"))
        {
            return trimmed;
        }
        if (NUMERIC_PATTERN.matcher(trimmed).matches())
        {
            return trimmed + TELEGRAM_ENTITY_SUFFIX;
        }
        return resolveUsername(context, trimmed);
    }

    /**
     * Resolves a Telegram username to the entity address {@code <id>@telegram.org}, first through
     * the local {@code users} table and then, as a fallback, through the Telegram API.
     *
     * @param context the per-update command context
     * @param username the raw username, with or without the leading '@'
     * @return the entity address, or {@code null} when the username cannot be resolved
     */
    private static String resolveUsername(HandlerContext context, String username)
    {
        String normalized = normalizeUsername(username);
        if (normalized == null)
        {
            return null;
        }

        if (context.database() != null && context.managers().users() != null)
        {
            Long userId = context.managers().users().getUserIdByUsername(normalized).orElse(null);
            if (userId != null && userId > 0)
            {
                return userId + TELEGRAM_ENTITY_SUFFIX;
            }
        }

        Long telegramId = resolveUsernameFromTelegram(context, normalized);
        if (telegramId == null || telegramId <= 0)
        {
            return null;
        }
        return telegramId + TELEGRAM_ENTITY_SUFFIX;
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
        if (context.telegramClient() == null)
        {
            return null;
        }
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
     * Normalizes a Telegram username for lookup.
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
