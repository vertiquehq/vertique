// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Integration proof that generated MCP invokers reach the AOP resilience proxy. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class McpResilienceIT {

    private static final String TOKEN_KEY = "super-secret-key-for-example-app-minimum-256-bits-long!!";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(
                    new McpExampleComponentVertiqueComponentFactory())
            .withConfig(testConfig());

    private static WebClient mcpClient;
    private static HttpClient rawHttpClient;
    private static String bearerToken;

    @BeforeAll
    static void setUp() {
        rawHttpClient = app.vertx().createHttpClient();
        mcpClient = WebClient.wrap(rawHttpClient);
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", TOKEN_KEY);
        bearerToken = auth.generateToken(
                new JsonObject().put("sub", "resilience-user").put("roles", List.of("user")));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (rawHttpClient != null) {
            await(rawHttpClient.close());
        }
    }

    @Test
    @Order(1)
    @DisplayName("maps an AOP timeout through the MCP failure contract without retrying")
    void shouldMapTimeoutAndInvokeTheHandlerOnlyOnce() throws Exception {
        McpResilienceObservationRecorder recorder = recorder();
        recorder.clear();
        McpResilienceProbe probe = probe();
        probe.reset();

        HttpResponse<Buffer> response = await(call("weather.resilient-timeout"));

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.getHeader("Cache-Control")).isEqualToIgnoringCase("no-store");
        assertThat(response.bodyAsString()).contains("-32603", "Request timed out");
        assertThat(probe.timeoutInvocations())
                .as("status=%s body=%s", response.statusCode(), response.bodyAsString())
                .isEqualTo(1);
        assertThat(recorder.events())
                .extracting(McpRequestCompletedEvent::transportOutcome)
                .containsExactly(McpTransportOutcome.WRITTEN);
        assertThat(recorder.events().getFirst().terminal().errorType().name()).isEqualTo("TIMEOUT");
    }

    @Test
    @Order(2)
    @DisplayName("passes cooperative cancellation through the resilient proxy and fences late completion")
    void shouldObserveDisconnectCancellationAndSuppressLateCompletion() throws Exception {
        McpResilienceObservationRecorder recorder = recorder();
        recorder.clear();
        McpResilienceProbe probe = probe();
        probe.reset();

        try (Socket socket = new Socket("127.0.0.1", app.httpPort())) {
            byte[] body = callBody("weather.resilient-disconnect").toBuffer().getBytes();
            OutputStream output = socket.getOutputStream();
            String headers = "POST /mcp/ HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + app.httpPort() + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Authorization: Bearer " + bearerToken + "\r\n"
                    + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                    + "Mcp-Method: tools/call\r\n"
                    + "Mcp-Name: weather.resilient-disconnect\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            awaitCondition(
                    () -> probe.disconnectInvocations() == 1,
                    "the disconnect-sensitive tool invocation must begin before the peer resets");
            socket.setSoLinger(true, 0);
        }

        await(probe.cancellationObserved());
        awaitCondition(
                () -> recorder.events().size() == 1,
                "the disconnected request must publish exactly one completion before its late result resolves");
        probe.completeDisconnectLate();

        assertThat(probe.disconnectInvocations()).isEqualTo(1);
        assertThat(recorder.events()).hasSize(1);
        assertThat(recorder.events().getFirst().transportOutcome())
                .isIn(McpTransportOutcome.RESET, McpTransportOutcome.DISCONNECTED, McpTransportOutcome.WRITE_FAILED);
    }

    private static McpExampleComponent component() {
        return app.component();
    }

    private static McpResilienceObservationRecorder recorder() {
        return component().mcpResilienceObservationRecorder();
    }

    private static McpResilienceProbe probe() {
        return component().mcpResilienceProbe();
    }

    private static Future<HttpResponse<Buffer>> call(String tool) {
        return mcpClient
                .post(app.httpPort(), "127.0.0.1", "/mcp/")
                .putHeader("content-type", "application/json")
                .putHeader("Authorization", "Bearer " + bearerToken)
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", tool)
                .sendBuffer(callBody(tool).toBuffer());
    }

    private static JsonObject callBody(String tool) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", tool)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put(
                                        "_meta",
                                        new JsonObject()
                                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()))
                                .put("name", tool)
                                .put("arguments", new JsonObject()));
    }

    private static JsonObject testConfig() {
        JsonObject tools = new JsonObject()
                .put("weather.resilient-timeout", new JsonObject().put("policy", "mcp-resilience"))
                .put("weather.resilient-disconnect", new JsonObject().put("policy", "mcp-resilience"));
        return new JsonObject()
                .put(
                        "http",
                        new JsonObject().put("port", 0).put("host", "127.0.0.1").put("idleTimeoutSeconds", 30))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                .put("management", new JsonObject().put("enabled", false))
                .put(
                        "mcp",
                        new JsonObject()
                                .put("enabled", true)
                                .put("serverName", "vertique-mcp-resilience-test")
                                .put("serverVersion", "1.0")
                                .put("authenticationScheme", "bearerAuth")
                                .put("rateLimit", new JsonObject().put("tools", tools)))
                .put("resilience", new JsonObject().put("policies", new JsonObject()))
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put("enabled", true)
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put("mcp-resilience", rateLimitPolicy(10))
                                                .put("mcp-shared", rateLimitPolicy(10))
                                                .put("mcp-observed", rateLimitPolicy(10))
                                                .put("mcp-ip", rateLimitPolicy(10))
                                                .put("mcp-double", rateLimitPolicy(10))));
    }

    private static JsonObject rateLimitPolicy(int capacity) {
        return new JsonObject()
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "OPEN")
                .put("revision", "v1")
                .put("defaultCost", 1)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", capacity)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "GREEDY")
                                                .put("tokens", capacity)
                                                .put("periodMs", 3_600_000)));
    }

    private static void awaitCondition(BooleanSupplier condition, String description) throws Exception {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        long timerId = app.vertx().setPeriodic(10, ignored -> {
            if (condition.getAsBoolean()) {
                completed.complete(null);
            }
        });
        try {
            completed.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError(description, timeout);
        } finally {
            app.vertx().cancelTimer(timerId);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
