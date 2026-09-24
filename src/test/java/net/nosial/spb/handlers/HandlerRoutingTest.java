package net.nosial.spb.handlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.HandlerRegistry;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that each command and inline button reaches the handler that owns it.
 *
 * <p>Routing used to be a hand-maintained chain in the update consumer; it is now the sum of every
 * handler's annotation. That makes it worth asserting end to end: a new handler declaring an
 * overlapping command or callback prefix would silently steal updates from an existing one, and
 * these tests are what would catch it.
 */
class HandlerRoutingTest
{
    private final HandlerRegistry registry = new HandlerRegistry();

    /**
     * Returns the name of the handler that claims the update.
     *
     * @param update the update to route
     * @return the handler's simple class name, or {@code "none"} when nothing claims it
     */
    private String route(Update update)
    {
        HandlerContext context = Contexts.template().withUpdate(update);
        Optional<Handler> handler = this.registry.routeFor(context);
        return handler.map(Handler::name).orElse("none");
    }

    @Nested
    @DisplayName("Routing commands")
    class Commands
    {
        @ParameterizedTest(name = "{0} is handled by {1}")
        @CsvSource({
                "/start,           StartHandler",
                "/help,            HelpHandler",
                "/settings,        SettingsHandler",
                "/language,        LanguageHandler",
                "/lang,            LanguageHandler",
                "/info 12345,      InfoHandler",
                "/evidence abc,    EvidenceHandler",
                "/link a b proxy,  LinkHandler",
                "/auth token,      AuthenticationHandler",
                "/authinfo,        AuthenticationHandler",
                "/deauth,          AuthenticationHandler",
                "/connect 1,       ChannelConnectHandler",
                "/blacklist a b,   BlacklistHandler",
                "/report,          ReportHandler",
                "/ping,            PingHandler",
        })
        @DisplayName("each command reaches its own handler")
        void routesCommands(String text, String expected)
        {
            assertEquals(expected, route(Updates.groupMessage(1, text.trim())));
        }

        @Test
        @DisplayName("the @botname suffix still routes")
        void routesWithBotSuffix()
        {
            assertEquals("HelpHandler", route(Updates.groupMessage(1, "/help@testbot")));
            assertEquals("InfoHandler", route(Updates.groupMessage(2, "/info@testbot 42")));
        }

        @Test
        @DisplayName("an unknown command is left unclaimed")
        void leavesUnknownCommandsAlone()
        {
            assertEquals("none", route(Updates.groupMessage(1, "/nosuchcommand")));
        }

        @Test
        @DisplayName("an ordinary group message is not claimed by a command handler")
        void leavesOrdinaryMessagesAlone()
        {
            assertEquals("none", route(Updates.groupMessage(1, "just chatting")));
        }

        @Test
        @DisplayName("a forwarded /start is a message to report, not an invocation")
        void forwardedStartIsNotAnInvocation()
        {
            // Forwarding a message into the bot's private chat is how a user reports it, so a
            // forwarded /start is somebody reporting the text "/start" rather than running it.
            Update forwarded = Updates.fromJson("""
                    {"update_id":1,"message":{"message_id":1,"date":1700000000,
                     "chat":{"id":42,"type":"private"},
                     "from":{"id":42,"is_bot":false,"first_name":"Tester"},
                     "forward_from":{"id":7,"is_bot":false,"first_name":"Someone"},
                     "text":"/start"}}""");

            assertEquals("ReportHandler", route(forwarded));
        }

        @Test
        @DisplayName("a forwarded message in a group is not a report")
        void forwardedGroupMessageIsNotAReport()
        {
            Update forwarded = Updates.fromJson("""
                    {"update_id":1,"message":{"message_id":1,"date":1700000000,
                     "chat":{"id":-1001234567890,"type":"supergroup","title":"Test Chat"},
                     "from":{"id":42,"is_bot":false,"first_name":"Tester"},
                     "forward_from":{"id":7,"is_bot":false,"first_name":"Someone"},
                     "text":"look at this"}}""");

            assertEquals("none", route(forwarded));
        }

        @Test
        @DisplayName("/settings in a private chat is claimed by secretary settings")
        void secretarySettingsOutranksSettingsInPrivate()
        {
            assertEquals("SecretarySettingsHandler", route(Updates.privateMessage(1, "/settings")));
        }

        @Test
        @DisplayName("/settings in a group belongs to the group settings handler")
        void groupSettingsGoesToSettingsHandler()
        {
            assertEquals("SettingsHandler", route(Updates.groupMessage(1, "/settings")));
        }

        @Test
        @DisplayName("a plain /start opens the normal start handler, not secretary mode")
        void plainStartIsNotSecretary()
        {
            assertEquals("StartHandler", route(Updates.privateMessage(1, "/start")));
        }

        @Test
        @DisplayName("a /start carrying the business deep link opens secretary settings")
        void businessStartOpensSecretarySettings()
        {
            assertEquals("SecretarySettingsHandler", route(Updates.privateMessage(1, "/start bizChat123")));
        }
    }

    @Nested
    @DisplayName("Routing inline buttons")
    class Callbacks
    {
        @ParameterizedTest(name = "{0} is handled by {1}")
        @CsvSource({
                "cfg:main,                    ConfigurationHandler",
                "help:main,                   HelpHandler",
                "lang:menu,                   LanguageHandler",
                "report:open,                 ReportHandler",
                "report_action:delete,        ReportActionHandler",
                "false-report:abc,            FalsePositiveHandler",
                "secset:open,                 SecretarySettingsHandler",
                "seccontact:allow,            SecretarySettingsHandler",
                "operator-report:close,       OperatorReportHandler",
        })
        @DisplayName("each callback prefix reaches its owner")
        void routesCallbacks(String data, String expected)
        {
            assertEquals(expected, route(Updates.callbackQuery(1, data.trim())));
        }

        @Test
        @DisplayName("a callback in no handler's namespace is left unclaimed")
        void leavesUnknownNamespacesAlone()
        {
            assertEquals("none", route(Updates.callbackQuery(1, "unknown:thing")));
        }

        @Test
        @DisplayName("no two handlers claim the same callback prefix")
        void callbackNamespacesDoNotOverlap()
        {
            List<String> prefixes = HandlerRoutingTest.this.registry.handlers().stream()
                    .flatMap(registered -> registered.callbackPrefixes().stream())
                    .toList();

            for (String prefix : prefixes)
            {
                long claimants = HandlerRoutingTest.this.registry.handlers().stream()
                        .filter(registered -> registered.callbackPrefixes().stream().anyMatch(prefix::startsWith))
                        .count();

                assertEquals(1, claimants, "callback prefix '" + prefix + "' is claimed by more than one handler");
            }
        }
    }

    @Nested
    @DisplayName("Routing Telegram Business updates")
    class Secretary
    {
        @Test
        @DisplayName("the connection itself is handled by the connection handler")
        void connectionGoesToConnectionHandler()
        {
            assertEquals("SecretaryConnectionHandler", route(Updates.businessConnection(1, true)));
            assertEquals("SecretaryConnectionHandler", route(Updates.businessConnection(2, false)));
        }

        @Test
        @DisplayName("a message arriving through the connection is handled by the message handler")
        void businessMessageGoesToMessageHandler()
        {
            assertEquals("SecretaryMessageHandler", route(Updates.businessMessage(1, "hello there")));
        }

        @Test
        @DisplayName("a business message deletion is handled by the message handler too")
        void deletionGoesToMessageHandler()
        {
            assertEquals("SecretaryMessageHandler", route(Updates.deletedBusinessMessages(1)));
        }

        @Test
        @DisplayName("a business message is not claimed by the group message handlers")
        void businessMessageIsNotAGroupMessage()
        {
            HandlerContext context = Contexts.template().withUpdate(Updates.businessMessage(1, "hello"));

            List<String> observers = HandlerRoutingTest.this.registry.observersFor(context).stream()
                    .map(Handler::name)
                    .toList();

            assertTrue(observers.isEmpty(),
                    "group bookkeeping and scanning are for chat messages, not business ones: " + observers);
        }
    }

    @Nested
    @DisplayName("Observing every update")
    class Observers
    {
        @Test
        @DisplayName("scanning sees updates no routed handler claims")
        void scanningSeesUnclaimedUpdates()
        {
            HandlerContext context = Contexts.template().withUpdate(Updates.groupMessage(1, "just chatting"));

            List<String> observers = HandlerRoutingTest.this.registry.observersFor(context).stream()
                    .map(Handler::name)
                    .toList();

            assertTrue(observers.contains("ScanningHandler"), observers.toString());
            assertEquals("none", route(Updates.groupMessage(1, "just chatting")));
        }

        @Test
        @DisplayName("scanning also sees commands, before the command handler runs")
        void scanningSeesCommandsToo()
        {
            HandlerContext context = Contexts.template().withUpdate(Updates.groupMessage(1, "/report"));

            assertTrue(HandlerRoutingTest.this.registry.observersFor(context).stream()
                    .anyMatch(handler -> "ScanningHandler".equals(handler.name())));
            assertEquals("ReportHandler", route(Updates.groupMessage(1, "/report")));
        }

        @Test
        @DisplayName("registration sees a message before anything answers it")
        void registrationSeesEveryMessage()
        {
            HandlerContext context = Contexts.template().withUpdate(Updates.privateMessage(1, "/start"));

            assertEquals("RegistrationHandler",
                    HandlerRoutingTest.this.registry.observersFor(context).get(0).name());
        }

        @Test
        @DisplayName("join protection only observes membership-shaped updates")
        void joinProtectionIsNarrow()
        {
            HandlerContext callback = Contexts.template().withUpdate(Updates.callbackQuery(1, "cfg:main"));

            assertTrue(HandlerRoutingTest.this.registry.observersFor(callback).stream()
                    .noneMatch(handler -> "JoinProtectionHandler".equals(handler.name())));
        }

        @Test
        @DisplayName("a member joining is observed by join protection")
        void joinProtectionSeesMembershipUpdates()
        {
            HandlerContext joined = Contexts.template().withUpdate(Updates.chatMemberJoined(1));

            assertTrue(HandlerRoutingTest.this.registry.observersFor(joined).stream()
                    .anyMatch(handler -> "JoinProtectionHandler".equals(handler.name())));
        }
    }
}
