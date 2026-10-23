package net.nosial.spb.classes.federation;

import com.sun.net.httpserver.HttpServer;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.jfederation.classes.Json;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.FederationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class Ofd2IntegrationTest
{
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    private HttpServer server;
    private FederationService federation;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile boolean bot;
    private volatile String name = "SpamProtectionBot";
    private volatile int configurationStatus = 200;
    private volatile int lookupStatus = 404;

    @BeforeEach
    void start() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + path + " " + body);
            int status = 200;
            String response;
            if (path.equals("/operators/self"))
            {
                response = """
                        {"uuid":"%s","name":"%s","disabled":false,
                         "permissions":{"client":true,"management":true,"operator":false},
                         "configuration":{"auto_assign_reports":true,"auto_assign_automated_reports":false,"is_bot":%s},
                         "created":1,"updated":2}
                        """.formatted(UUID, name, bot);
            }
            else if (path.endsWith("/configuration"))
            {
                status = configurationStatus;
                if (status == 200) bot = true;
                response = "{\"auto_assign_reports\":true,\"auto_assign_automated_reports\":false,\"is_bot\":true}";
            }
            else if (path.equals("/blacklist"))
            {
                status = 201;
                response = "\"" + UUID + "\"";
            }
            else
            {
                status = lookupStatus;
                response = "{\"message\":\"upstream not found\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        federation = new FederationService("http://127.0.0.1:" + server.getAddress().getPort(), "bot-token");
    }

    @AfterEach
    void stop()
    {
        federation.close();
        server.stop(0);
    }

    @Test
    void configuresOnlyBotFlagAndPreservesAssignmentSettings() throws Exception
    {
        var operator = federation.authenticate();
        assertTrue(federation.isAuthenticated());
        assertTrue(operator.isBot());
        assertTrue(operator.isAutoAssignReports());
        assertFalse(operator.isAutoAssignAutomatedReports());
        assertEquals("PATCH /operators/" + UUID + "/configuration {\"is_bot\":true}", requests.get(1));
        federation.authenticate();
        assertEquals(1, requests.stream().filter(r -> r.startsWith("PATCH")).count());
    }

    @Test
    void humanAuthenticationDoesNotChangeConfiguration() throws Exception
    {
        assertFalse(federation.operatorFor("human-token").isBot());
        assertEquals(1, requests.size());
        assertFalse(federation.isAuthenticated());
    }

    @Test
    void configurationFailureRetainsTokenAndRetriesBeforeEnablingWrites() throws Exception
    {
        configurationStatus = 403;
        assertThrows(FederationException.class, federation::authenticate);
        assertFalse(federation.isAuthenticated());
        assertTrue(federation.isAuthenticationPending());
        assertThrows(FederationException.class, () -> federation.publishEntity("telegram.org", "42", null));
        configurationStatus = 200;
        federation.authenticate();
        assertTrue(federation.isAuthenticated());
    }

    @Test
    void reservedOperatorsRequireDedicatedCredentials()
    {
        name = "root";
        assertTrue(assertThrows(FederationException.class, federation::authenticate).getMessage().contains("dedicated"));
        assertFalse(federation.isAuthenticated());
        assertEquals(1, requests.size());
    }

    @Test
    void blacklistWithoutReportSupportsNewIncidentTypes() throws Exception
    {
        assertEquals(UUID, federation.blacklistEntity("human-token", "42@telegram.org", null, IncidentType.TRACKING, null));
        String request = requests.get(0);
        var body = Json.readTree(request.substring(request.indexOf('{')));
        assertEquals("TRACKING", body.get("type").asString());
        assertFalse(body.has("report_uuid"));
        assertFalse(body.has("expires"));
    }

    @Test
    void absenceUsesHttpStatusRatherThanErrorMessage() throws Exception
    {
        assertTrue(federation.entity("42@telegram.org").isEmpty());
        lookupStatus = 500;
        assertThrows(FederationException.class, () -> federation.entity("42@telegram.org"));
    }
}
