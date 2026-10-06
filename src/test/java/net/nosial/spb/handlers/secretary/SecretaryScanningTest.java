package net.nosial.spb.handlers.secretary;

import com.sun.net.httpserver.HttpServer;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the secretary's scanning toggle decides whether a message's content reaches
 * Federation, while the sender is queried either way.
 *
 * <p>A local server stands in for Federation and records every request it receives; it answers
 * with an error, which the secretary treats as an unavailable analysis.
 */
class SecretaryScanningTest
{
    private static final String SECRET = "my bank PIN is 4921";

    @TempDir
    Path directory;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange ->
        {
            byte[] body = exchange.getRequestBody().readAllBytes();
            this.requests.add(exchange.getRequestURI() + "\n" + new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stopServer()
    {
        this.server.stop(0);
    }

    private void recommend(SecretaryConfiguration configuration)
    {
        FederationService federation = new FederationService(
                "http://127.0.0.1:" + this.server.getAddress().getPort() + "/", null);
        Message message = Updates.businessMessage(1, SECRET).getBusinessMessage();
        SecretaryMessageHandler.recommend(Contexts.template(this.directory, federation), configuration, message,
                message.getFrom());
    }

    private boolean contentSent()
    {
        return this.requests.stream().anyMatch(request -> request.contains(SECRET));
    }

    private boolean senderQueried(long senderId)
    {
        return this.requests.stream().anyMatch(request -> request.contains(senderId + "@telegram.org")
                || request.contains(senderId + "%40telegram.org"));
    }

    @ParameterizedTest
    @EnumSource(value = ScanningBehavior.class, names = {"PASSIVE", "STRICT"})
    @DisplayName("with scanning off, the message content is never sent but the sender is still queried")
    void scanningOffSendsOnlyTheSender(ScanningBehavior behavior)
    {
        long senderId = Updates.businessMessage(1, SECRET).getBusinessMessage().getFrom().getId();

        recommend(new SecretaryConfiguration(42L, "conn", behavior, false, false));

        assertFalse(contentSent(), "content reached Federation: " + this.requests);
        assertTrue(senderQueried(senderId), "the sender was not queried: " + this.requests);
    }

    @ParameterizedTest
    @EnumSource(value = ScanningBehavior.class, names = {"PASSIVE", "STRICT"})
    @DisplayName("with scanning on, the message content is sent for analysis")
    void scanningOnSendsTheContent(ScanningBehavior behavior)
    {
        recommend(new SecretaryConfiguration(42L, "conn", behavior, false, true));

        assertTrue(contentSent(), "content did not reach Federation: " + this.requests);
    }
}
