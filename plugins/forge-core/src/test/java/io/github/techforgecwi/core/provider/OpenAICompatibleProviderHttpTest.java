package io.github.techforgecwi.core.provider;

import com.sun.net.httpserver.HttpServer;
import io.github.techforgecwi.core.ForgeAIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real HTTP code against a tiny fake provider on localhost. */
class OpenAICompatibleProviderHttpTest {

    private HttpServer server;
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello, seeker.\"}}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAICompatibleProvider provider(String key) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        return new OpenAICompatibleProvider(new OpenAICompatibleProvider.Settings(
                base, "tiny", key, Duration.ofSeconds(5), 100, "max_tokens"));
    }

    private static final List<ChatProvider.Message> HI = List.of(new ChatProvider.Message("user", "hi"));

    @Test
    void sendsBearerKeyToChatCompletions() {
        assertEquals("Hello, seeker.", provider("sk-test").complete(HI).join());
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer sk-test", authHeader.get());
    }

    @Test
    void omitsAuthHeaderWithoutKey() {
        provider("").complete(HI).join();
        assertNull(authHeader.get());
    }

    @Test
    void rateLimitBecomesFriendlyMessage() {
        status = 429;
        reply = "{\"error\":\"slow down\"}";
        CompletionException error = assertThrows(CompletionException.class, () -> provider("k").complete(HI).join());
        ForgeAIException forge = assertInstanceOf(ForgeAIException.class, error.getCause());
        assertTrue(forge.playerMessage().contains("busy"));
    }

    @Test
    void serverErrorDoesNotLeakDetailsToPlayers() {
        status = 500;
        reply = "internal stack trace with secrets";
        CompletionException error = assertThrows(CompletionException.class, () -> provider("k").complete(HI).join());
        ForgeAIException forge = assertInstanceOf(ForgeAIException.class, error.getCause());
        assertTrue(forge.getMessage().contains("HTTP 500"));
        assertTrue(!forge.playerMessage().contains("secrets"));
    }
}
