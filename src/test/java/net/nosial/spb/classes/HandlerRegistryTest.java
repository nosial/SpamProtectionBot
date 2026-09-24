package net.nosial.spb.classes;

import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.exceptions.HandlerException;
import net.nosial.spb.handlers.StartHandler;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for annotation-driven handler discovery and routing.
 */
class HandlerRegistryTest
{
    /** The package holding the handler fixtures used by these tests. */
    private static final String TEST_PACKAGE = "net.nosial.spb.testhandlers";

    private HandlerRegistry registry;

    @BeforeEach
    void discoverTestHandlers()
    {
        this.registry = new HandlerRegistry(TEST_PACKAGE);
    }

    /**
     * Binds the given update to a test context.
     *
     * @param update the update to bind
     * @return the per-update context
     */
    private static HandlerContext contextFor(Update update)
    {
        return Contexts.template().withUpdate(update);
    }

    /**
     * Returns the simple class name of the handler that claims the update.
     *
     * @param update the update to route
     * @return the handler name, or {@code "none"} when no handler claimed it
     */
    private String routeName(Update update)
    {
        Optional<Handler> handler = this.registry.routeFor(contextFor(update));
        return handler.map(Handler::name).orElse("none");
    }

    @Nested
    @DisplayName("Discovering handlers")
    class Discovery
    {
        @Test
        @DisplayName("annotated classes in the package are registered")
        void registersAnnotatedClasses()
        {
            List<String> names = HandlerRegistryTest.this.registry.handlers().stream()
                    .map(registered -> registered.handler().name())
                    .toList();

            assertTrue(names.contains("PingHandler"), names.toString());
            assertTrue(names.contains("DemoCallbackHandler"), names.toString());
            assertTrue(names.contains("ObservingHandler"), names.toString());
        }

        @Test
        @DisplayName("a handler marked disabled is not registered")
        void skipsDisabledHandlers()
        {
            List<String> names = HandlerRegistryTest.this.registry.handlers().stream()
                    .map(registered -> registered.handler().name())
                    .toList();

            assertFalse(names.contains("DisabledHandler"), names.toString());
        }

        @Test
        @DisplayName("observers and routed handlers are kept apart")
        void separatesDispatchModes()
        {
            long observers = HandlerRegistryTest.this.registry.handlers().stream()
                    .filter(registered -> registered.mode() == DispatchMode.OBSERVE)
                    .count();
            long routed = HandlerRegistryTest.this.registry.handlers().stream()
                    .filter(registered -> registered.mode() == DispatchMode.ROUTE)
                    .count();

            assertEquals(3, observers);
            assertEquals(4, routed);
            assertEquals(7, HandlerRegistryTest.this.registry.size());
        }

        @Test
        @DisplayName("an unknown package registers nothing")
        void unknownPackageIsEmpty()
        {
            assertEquals(0, new HandlerRegistry("net.nosial.spb.nothing.here").size());
        }

        @Test
        @DisplayName("every production handler is registered")
        void discoversProductionHandlers()
        {
            List<String> names = new HandlerRegistry().handlers().stream()
                    .map(registered -> registered.handler().name())
                    .sorted()
                    .toList();

            assertEquals(List.of("AuthenticationHandler", "BlacklistHandler", "ChannelConnectHandler",
                    "ConfigurationHandler", "EvidenceHandler", "FalsePositiveHandler", "HelpHandler",
                    "InfoHandler", "JoinProtectionHandler", "LanguageHandler", "LinkHandler",
                    "OperatorReportHandler", "PingHandler", "RegistrationHandler", "ReportActionHandler",
                    "ReportHandler", "ScanningHandler", "SecretaryConnectionHandler", "SecretaryMessageHandler",
                    "SecretarySettingsHandler", "SettingsHandler", "StartHandler"), names);
        }

        @Test
        @DisplayName("/start routes to the start handler")
        void routesStartCommand()
        {
            Optional<Handler> handler = new HandlerRegistry()
                    .routeFor(contextFor(Updates.privateMessage(1, "/start")));

            assertTrue(handler.isPresent());
            assertInstanceOf(StartHandler.class, handler.get());
        }

        @Test
        @DisplayName("bookkeeping observes a message before scanning does")
        void observersRunInDependencyOrder()
        {
            HandlerRegistry production = new HandlerRegistry();

            List<String> observers = production.observersFor(contextFor(Updates.groupMessage(1, "hello"))).stream()
                    .map(Handler::name)
                    .toList();

            // Scanning reads the sender and the administrator list that registration writes, so
            // the order here is a dependency, not a preference.
            assertEquals(List.of("RegistrationHandler", "ScanningHandler", "JoinProtectionHandler"), observers);
        }
    }

    @Nested
    @DisplayName("Ordering handlers")
    class Ordering
    {
        @Test
        @DisplayName("observers run in descending priority order")
        void ordersObserversByPriority()
        {
            List<String> names = HandlerRegistryTest.this.registry
                    .observersFor(contextFor(Updates.privateMessage(1, "hello"))).stream()
                    .map(Handler::name)
                    .toList();

            assertEquals(List.of("FailingObservingHandler", "ObservingHandler", "SecondObservingHandler"), names);
        }

        @Test
        @DisplayName("a higher-priority handler claims the update first")
        void higherPriorityWins()
        {
            assertEquals("VetoingPingHandler", routeName(Updates.privateMessage(1, "/ping mine")));
        }

        @Test
        @DisplayName("a handler that declines passes the update to the next one")
        void decliningPassesOn()
        {
            assertEquals("PingHandler", routeName(Updates.privateMessage(2, "/ping other")));
        }
    }

    @Nested
    @DisplayName("Matching updates")
    class Matching
    {
        @Test
        @DisplayName("a command handler claims its command")
        void matchesCommand()
        {
            assertEquals("PingHandler", routeName(Updates.privateMessage(1, "/ping")));
        }

        @Test
        @DisplayName("the @botname suffix is accepted")
        void matchesCommandWithBotSuffix()
        {
            assertEquals("PingHandler", routeName(Updates.groupMessage(2, "/ping@testbot")));
        }

        @Test
        @DisplayName("command matching ignores case")
        void matchesCommandIgnoringCase()
        {
            assertEquals("PingHandler", routeName(Updates.privateMessage(3, "/PING")));
        }

        @Test
        @DisplayName("an unclaimed command falls through to the catch-all message handler")
        void fallsThroughToCatchAll()
        {
            assertEquals("CatchAllMessageHandler", routeName(Updates.privateMessage(4, "/unknown")));
        }

        @Test
        @DisplayName("a callback handler claims its data prefix")
        void matchesCallbackPrefix()
        {
            assertEquals("DemoCallbackHandler", routeName(Updates.callbackQuery(5, "demo:open")));
        }

        @Test
        @DisplayName("a callback outside the prefix is left unclaimed")
        void ignoresOtherCallbackPrefixes()
        {
            assertEquals("none", routeName(Updates.callbackQuery(6, "other:open")));
        }

        @Test
        @DisplayName("an update no handler serves is left unclaimed")
        void leavesUnknownUpdatesUnclaimed()
        {
            assertEquals("none", routeName(Updates.poll(7)));
        }

        @Test
        @DisplayName("observers see updates no routed handler claims")
        void observersSeeEverything()
        {
            assertEquals(3, HandlerRegistryTest.this.registry.observersFor(contextFor(Updates.poll(8))).size());
        }
    }

    @Nested
    @DisplayName("Rejecting invalid declarations")
    class InvalidDeclarations
    {
        @Test
        @DisplayName("a class without the annotation is rejected")
        void rejectsUnannotatedHandler()
        {
            HandlerException e = assertThrows(HandlerException.class, () -> new HandlerRegistry(new Unannotated()));
            assertTrue(e.getMessage().contains("@UpdateHandler"), e.getMessage());
        }

        @Test
        @DisplayName("declaring commands without the COMMAND type is rejected")
        void rejectsUnreachableCommandFilter()
        {
            HandlerException e = assertThrows(HandlerException.class,
                    () -> new HandlerRegistry(new UnreachableCommands()));
            assertTrue(e.getMessage().contains("UpdateType.COMMAND"), e.getMessage());
        }

        @Test
        @DisplayName("declaring callback data without the CALLBACK_QUERY type is rejected")
        void rejectsUnreachableCallbackFilter()
        {
            HandlerException e = assertThrows(HandlerException.class,
                    () -> new HandlerRegistry(new UnreachableCallbacks()));
            assertTrue(e.getMessage().contains("UpdateType.CALLBACK_QUERY"), e.getMessage());
        }

        @Test
        @DisplayName("an empty command name is rejected")
        void rejectsEmptyCommand()
        {
            assertThrows(HandlerException.class, () -> new HandlerRegistry(new EmptyCommand()));
        }
    }

    /** A handler that forgot its annotation. */
    static final class Unannotated extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
        }
    }

    /** Declares commands it could never receive. */
    @UpdateHandler(value = UpdateType.CALLBACK_QUERY, commands = "nope")
    static final class UnreachableCommands extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
        }
    }

    /** Declares callback data it could never receive. */
    @UpdateHandler(value = UpdateType.MESSAGE, callbackData = "x:")
    static final class UnreachableCallbacks extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
        }
    }

    /** Declares a command name that is not a name. */
    @UpdateHandler(value = UpdateType.COMMAND, commands = " ")
    static final class EmptyCommand extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
        }
    }
}
