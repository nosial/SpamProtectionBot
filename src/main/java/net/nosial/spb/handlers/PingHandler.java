package net.nosial.spb.handlers;

import net.nosial.jfederation.records.OperatorRecord;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles the {@code /ping} command: available in every chat the bot answers commands in, except
 * a channel (which never delivers a command to a handler in the first place, since a channel post
 * arrives as a different update kind than a message).
 *
 * <p>It is a double check rather than a single one. Answering at all demonstrates the bot itself is
 * alive and processing updates, and the reply's own text reports how far behind it is doing so; a
 * second, independent check asks the configured Federation server for its own information and times
 * that round trip separately, so a Federation outage and a bot outage are never confused for one
 * another. When the caller has stored Federation operator credentials of their own, the same
 * opportunity verifies those live and reports their standing, exactly as {@code /authinfo} would,
 * folded into one place a moderator already checks.
 *
 * <p>Nothing here is sensitive: the Federation figures shown are the same public counts already on
 * the {@code /start} screen and the settings menu, and the operator block never carries the
 * operator's own access token, only what the server would tell the operator about themselves.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "ping")
public final class PingHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PingHandler.class);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        // A channel post never reaches a COMMAND handler (it arrives as CHANNEL_POST, not
        // MESSAGE), so this is unreachable in practice; kept explicit so the exclusion is not
        // merely an accident of how update routing happens to work today.
        if ("channel".equals(message.getChat().getType()))
        {
            return;
        }

        Language lang = resolveLanguage(context, message);
        LanguageManager lm = context.languages();

        long updateLatencyMs = Math.max(0, System.currentTimeMillis() - message.getDate() * 1000L);

        StringBuilder html = new StringBuilder(lm.get(lang, "ping", "bot_status", lm.get(lang, "ping", "status_online")));
        html.append(lm.get(lang, "ping", "bot_latency", String.valueOf(updateLatencyMs)));

        html.append(federationHtml(context, lang));

        Optional<OperatorIdentity> identity = context.managers().operators().getOperator(message.getFrom().getId());
        identity.ifPresent(operatorIdentity -> html.append(operatorHtml(context, lang, message.getFrom().getId(), operatorIdentity)));
        sendReply(context, message, html.toString());
    }

    /**
     * Builds the Federation section: whether a server is configured, reachable, and how the bot's
     * own token was treated, timed independently of the bot's own {@link #handle} latency so a slow
     * or down Federation server is never mistaken for a slow or down bot.
     *
     * @param context the per-update command context
     * @param lang the resolved language
     * @return the section's HTML, always ending in a blank line
     */
    private static String federationHtml(HandlerContext context, Language lang)
    {
        LanguageManager lm = context.languages();
        FederationService federation = context.federation();

        StringBuilder html = new StringBuilder(lm.get(lang, "ping", "federation_header"));
        if (!federation.isAvailable())
        {
            html.append(lm.get(lang, "ping", "federation_status",
                    lm.get(lang, "ping", "status_not_configured")));
            html.append('\n');
            return html.toString();
        }

        long start = System.nanoTime();
        ServerInformation serverInformation;
        try
        {
            serverInformation = federation.serverInformation();
        }
        catch (FederationException e)
        {
            LOGGER.debug("Federation server unreachable during /ping: {}", e.getMessage());
            html.append(lm.get(lang, "ping", "federation_status",
                    lm.get(lang, "ping", "status_unreachable")));
            html.append('\n');
            return html.toString();
        }

        long federationLatencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        html.append(lm.get(lang, "ping", "federation_status", lm.get(lang, "ping", "status_available")));
        html.append(lm.get(lang, "ping", "federation_access", lm.get(lang, "ping", federation.isAuthenticated() ? "access_authenticated" : "access_anonymous")));
        html.append(lm.get(lang, "ping", "federation_latency", String.valueOf(federationLatencyMs)));
        html.append(lm.get(lang, "ping", "federation_server", HtmlEscape.escape(serverInformation.serverName()), HtmlEscape.escape(serverInformation.apiVersion())));
        html.append(lm.get(lang, "ping", "federation_stat_entities", String.valueOf(serverInformation.knownEntities())));
        html.append(lm.get(lang, "ping", "federation_stat_blacklist", String.valueOf(serverInformation.blacklistRecords())));
        html.append(lm.get(lang, "ping", "federation_stat_reports", String.valueOf(serverInformation.reports())));
        html.append(lm.get(lang, "ping", "federation_stat_evidence", String.valueOf(serverInformation.evidenceRecords())));
        html.append(lm.get(lang, "ping", "federation_stat_attachments", String.valueOf(serverInformation.fileAttachmentRecords())));
        html.append(lm.get(lang, "ping", "federation_stat_operators", String.valueOf(serverInformation.operators())));
        html.append('\n');
        return html.toString();
    }

    /**
     * Builds the optional operator section: only shown when the caller has a Federation operator
     * credential of their own, verified live the same way {@code /authinfo} verifies it. A
     * credential the server no longer honours is reported as such rather than shown stale.
     *
     * @param context the per-update command context
     * @param lang the resolved language
     * @param userId the calling Telegram user's id
     * @param identity the caller's stored operator credential
     * @return the section's HTML
     */
    private static String operatorHtml(HandlerContext context, Language lang, long userId, OperatorIdentity identity)
    {
        LanguageManager lm = context.languages();
        StringBuilder html = new StringBuilder(lm.get(lang, "ping", "operator_header"));

        OperatorRecord operator;
        try
        {
            operator = context.federation().operatorFor(identity.accessToken());
        }
        catch (FederationException | IllegalArgumentException e)
        {
            LOGGER.debug("Could not verify operator credential for Telegram user {} during /ping: {}", userId, e.getMessage());
            html.append(lm.get(lang, "ping", "operator_unavailable"));
            return html.toString();
        }

        html.append(lm.get(lang, "ping", "operator_name", HtmlEscape.escape(operator.name())));
        html.append(lm.get(lang, "ping", "operator_status", lm.get(lang, "ping", operator.disabled() ? "operator_status_disabled" : "operator_status_active")));
        html.append(lm.get(lang, "ping", "operator_permissions", permissionSummary(context, lang, operator)));
        return html.toString();
    }

    /**
     * Lists the operator's granted permissions by name, comma-separated.
     *
     * @param context the per-update command context
     * @param lang the resolved language
     * @param operator the operator record
     * @return the permission names, or a placeholder when none are granted
     */
    private static String permissionSummary(HandlerContext context, Language lang, OperatorRecord operator)
    {
        LanguageManager lm = context.languages();
        List<String> granted = new ArrayList<>();
        if (operator.clientPermissions())
        {
            granted.add(lm.get(lang, "ping", "permission_client"));
        }
        if (operator.managementPermissions())
        {
            granted.add(lm.get(lang, "ping", "permission_management"));
        }
        if (operator.operatorPermissions())
        {
            granted.add(lm.get(lang, "ping", "permission_operator"));
        }
        if (granted.isEmpty())
        {
            return lm.get(lang, "ping", "permission_none");
        }
        return String.join(", ", granted);
    }
}
