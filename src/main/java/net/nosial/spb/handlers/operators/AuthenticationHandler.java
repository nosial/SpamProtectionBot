package net.nosial.spb.handlers.operators;

import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.jfederation.records.OperatorRecord;
import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.managers.OperatorManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.utilities.HtmlEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

/**
 * Handles an individual Telegram user's Federation operator credentials.
 *
 * <p>{@code /auth <access-token>} validates a token with the Federation server before
 * atomically persisting it against the caller's Telegram user id. {@code /deauth} removes that
 * persisted credential. {@code /authinfo} creates a short-lived client with the caller's stored
 * credential and returns the current operator record from {@code getSelf()}. The process-wide
 * Federation client is never mutated, so one user's command cannot authenticate another user's
 * Federation request.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = {"auth", "deauth", "authinfo"})
public final class AuthenticationHandler extends Handler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationHandler.class);

    @Override
    public void handle(HandlerContext context) throws TelegramApiException
    {
        Message message = context.update().getMessage();
        if (message == null || message.getFrom() == null)
        {
            return;
        }

        Language lang = resolveLanguage(context, message);

        if (isCommand(context.update(), "auth"))
        {
            authenticate(context, message, lang);
        }
        else if (isCommand(context.update(), "deauth"))
        {
            deauthenticate(context, message, lang);
        }
        else
        {
            showAuthenticationInfo(context, message, lang);
        }
    }

    /**
     * Authenticates a user with the Federation server using an access token and stores the user credentials.
     * If the federation service is unavailable or any error occurs during the process, appropriate messages
     * are sent to the user.
     *
     * @param context the handler execution context containing necessary utilities and managers
     * @param message the incoming message containing the user's details and possible access token
     * @param lang the language preference for the user, to localize reply messages
     * @throws TelegramApiException if there is an issue with sending the reply message to the Telegram user
     */
    private static void authenticate(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        if (!context.federation().isAvailable())
        {
            sendReply(context, message, context.languages().get(lang, "authentication", "unavailable"));
            return;
        }

        String accessToken = singleArgument(message);
        if (accessToken == null)
        {
            sendReply(context, message, context.languages().get(lang, "authentication", "usage"));
            return;
        }

        long userId = message.getFrom().getId();
        try
        {
            OperatorRecord operator = authenticate(context.managers().operators(), context.federation(), accessToken, userId);
            sendReply(context, message, context.languages().get(lang, "authentication", "authenticated", HtmlEscape.escape(operator.name()), HtmlEscape.escape(operator.uuid())));
        }
        catch (FederationException | IllegalArgumentException e)
        {
            LOGGER.info("Federation authentication rejected for Telegram user {}: {}", userId, e.getMessage());
            sendReply(context, message, context.languages().get(lang, "authentication", "rejected"));
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to persist operator authentication for Telegram user {}: {}", userId, e.getMessage());
            sendReply(context, message, context.languages().get(lang, "authentication", "credential_store_failed"));
        }
    }

    /**
     * Deauthenticates a Telegram user by removing their operator authentication credentials.
     * Sends a localized reply to the user indicating whether the deauthentication process
     * was successful or failed.
     *
     * @param context the handler execution context containing necessary utilities and managers
     * @param message the incoming message containing the user's details
     * @param lang the language preference for the user, to localize reply messages
     * @throws TelegramApiException if there is an issue with sending the reply message to the Telegram user
     */
    private static void deauthenticate(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        long userId = message.getFrom().getId();
        try
        {
            context.managers().operators().deleteOperator(userId);
            sendReply(context, message, context.languages().get(lang, "authentication", "signed_out"));
        }
        catch (DatabaseException e)
        {
            LOGGER.warn("Failed to remove operator authentication for Telegram user {}: {}", userId, e.getMessage());
            sendReply(context, message, context.languages().get(lang, "authentication", "signout_failed"));
        }
    }

    /**
     * Displays the authentication information for the user based on their current authentication
     * status with the Federation server. If the Federation service is unavailable, notifies the user
     * accordingly. If the user is not authenticated, sends a localized response. Otherwise, retrieves
     * and presents the operator details.
     *
     * @param context the handler execution context containing necessary utilities and services
     * @param message the incoming message containing the user's details
     * @param lang the language preference for the user, used to localize reply messages
     * @throws TelegramApiException if there is an issue with sending the reply message to the Telegram user
     */
    private static void showAuthenticationInfo(HandlerContext context, Message message, Language lang) throws TelegramApiException
    {
        if (!context.federation().isAvailable())
        {
            sendReply(context, message, context.languages().get(lang, "authentication", "unavailable"));
            return;
        }

        long userId = message.getFrom().getId();
        Optional<OperatorIdentity> identity = context.managers().operators().getOperator(userId);
        if (identity.isEmpty())
        {
            sendReply(context, message, context.languages().get(lang, "authentication", "not_authenticated"));
            return;
        }

        try
        {
            OperatorRecord operator = context.federation().operatorFor(identity.get().accessToken());
            sendReply(context, message, operatorInfo(context, lang, operator));
        }
        catch (FederationException | IllegalArgumentException e)
        {
            LOGGER.info("Federation authentication lookup failed for Telegram user {}: {}", userId, e.getMessage());
            sendReply(context, message, context.languages().get(lang, "authentication", "authinfo_unavailable"));
        }
    }

    /**
     * Identifies an access token with the Federation server and stores the credential locally.
     *
     * <p>The credential is only written once the server has accepted the token, so a rejected
     * token never leaves a stored credential that would fail on every later use.
     *
     * @param operators the operator credential store
     * @param federation the Federation server
     * @param accessToken the token to authenticate with
     * @param userId the Telegram user the credential belongs to
     * @return the operator record the token belongs to
     * @throws FederationException If the server is unavailable or rejected the token
     * @throws DatabaseException If the credential cannot be stored
     */
    static OperatorRecord authenticate(OperatorManager operators, FederationService federation, String accessToken,
                                       long userId) throws FederationException, DatabaseException
    {
        OperatorRecord operator = federation.operatorFor(accessToken);
        operators.saveOperator(userId, new OperatorIdentity(operator.uuid(), accessToken));
        return operator;
    }

    /**
     * Retrieves detailed information about an operator, including their name, UUID, status, and permissions,
     * formatted in the specified language context.
     *
     * @param context the handler execution context providing access to language utilities and other resources
     * @param lang the language in which to localize the operator information
     * @param operator the operator record containing details about the operator
     * @return a localized string containing the operator's information and formatted permissions
     */
    static String operatorInfo(HandlerContext context, Language lang, OperatorRecord operator)
    {
        return context.languages().get(lang, "authentication", "operator_header")
                + context.languages().get(lang, "authentication", "name", HtmlEscape.escape(operator.name())) + "\n"
                + context.languages().get(lang, "authentication", "uuid", HtmlEscape.escape(operator.uuid())) + "\n"
                + context.languages().get(lang, "authentication", operator.disabled() ? "status_disabled" : "status_active") + "\n\n"
                + context.languages().get(lang, "authentication", "permissions_header")
                + context.languages().get(lang, "authentication", "permission_client", permission(context, lang, operator.clientPermissions())) + "\n"
                + context.languages().get(lang, "authentication", "permission_operator", permission(context, lang, operator.operatorPermissions())) + "\n"
                + context.languages().get(lang, "authentication", "permission_management", permission(context, lang, operator.managementPermissions())) + "\n"
                + context.languages().get(lang, "authentication", "permission_auto_assign", permission(context, lang, operator.autoAssign()));
    }

    /**
     * Generates a localized string indicating whether a permission is enabled or disabled
     * based on the provided language context and permission state.
     *
     * @param context the handler execution context providing access to language utilities
     * @param lang the language in which to localize the permission status
     * @param granted a boolean indicating whether the permission is granted (true) or not (false)
     * @return a localized string representing the status of the permission, either "enabled" or "disabled"
     */
    private static String permission(HandlerContext context, Language lang, boolean granted)
    {
        return context.languages().get(lang, "general", granted ? "enabled" : "disabled");
    }
}
