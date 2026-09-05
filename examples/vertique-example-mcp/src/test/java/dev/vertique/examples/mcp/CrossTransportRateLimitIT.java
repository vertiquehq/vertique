// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Cross-transport proof for the MCP example's shared rate-limit composition. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CrossTransportRateLimitIT {

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
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        rawHttpClient = app.vertx().createHttpClient();
        mcpClient = WebClient.wrap(rawHttpClient);
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", TOKEN_KEY);
        bearerToken = auth.generateToken(
                new JsonObject().put("sub", "cross-transport-user").put("roles", List.of("user")));
    }

    @AfterAll
    static void tearDown() throws Exception {
        RestAssured.reset();
        if (rawHttpClient != null) {
            await(rawHttpClient.close());
        }
    }

    @Test
    @Order(1)
    @DisplayName("shares one authenticated bucket between REST and MCP")
    void shouldShareOneBucketAcrossARestRequestAndAnMcpToolCallForTheSamePrincipalAndPolicy() throws Exception {
        given().auth()
                .oauth2(bearerToken)
                .when()
                .get("/cross-transport/principal")
                .then()
                .statusCode(200);

        assertThat(await(call(
                                "weather.current",
                                new JsonObject().put("request", new JsonObject().put("locationName", "Helsinki"))))
                        .statusCode())
                .isEqualTo(200);
        assertThat(await(call(
                                "weather.current",
                                new JsonObject().put("request", new JsonObject().put("locationName", "Helsinki"))))
                        .statusCode())
                .isEqualTo(429);
    }

    @Test
    @Order(2)
    @DisplayName("keeps the shared-engine observer shape transport neutral")
    void shouldPreserveTransportNeutralRateLimitObserverShapeForMcpCalls() throws Exception {
        recorder().clear();
        given().auth()
                .oauth2(bearerToken)
                .when()
                .get("/cross-transport/observed")
                .then()
                .statusCode(200);
        assertThat(await(call("weather.observed", new JsonObject())).statusCode())
                .isEqualTo(200);

        List<RateLimitDecisionCompleted> events = recorder().events().stream()
                .filter(RateLimitDecisionCompleted.class::isInstance)
                .map(RateLimitDecisionCompleted.class::cast)
                .filter(event -> event.policyName().equals("mcp-observed"))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(java.util.Arrays.stream(RateLimitDecisionCompleted.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList())
                .containsExactly(
                        "policyName",
                        "policyRevision",
                        "mode",
                        "algorithm",
                        "outcome",
                        "cost",
                        "capacity",
                        "remaining",
                        "retryAfter",
                        "resetAfter",
                        "failureCode",
                        "backendLatencyNanos");
        RateLimitDecisionCompleted rest = events.get(0);
        RateLimitDecisionCompleted mcp = events.get(1);
        assertThat(mcp.policyName()).isEqualTo(rest.policyName());
        assertThat(mcp.policyRevision()).isEqualTo(rest.policyRevision());
        assertThat(mcp.mode()).isEqualTo(rest.mode());
        assertThat(mcp.algorithm()).isEqualTo(rest.algorithm());
        assertThat(mcp.cost()).isEqualTo(rest.cost());
        assertThat(mcp.capacity()).isEqualTo(rest.capacity());
        assertThat(mcp.outcome()).isEqualTo(rest.outcome());
    }

    @Test
    @Order(3)
    @DisplayName("uses the captured direct client IP rather than raw forwarded headers")
    void shouldShareDirectPeerIpBucketAcrossRestAndMcpWhileIgnoringForwardedHeaders() throws Exception {
        given().header("X-Forwarded-For", "198.51.100.10")
                .when()
                .get("/cross-transport/ip")
                .then()
                .statusCode(200);

        assertThat(await(call("weather.ip", new JsonObject(), "192.0.2.44")).statusCode())
                .isEqualTo(429);
    }

    @Test
    @Order(4)
    @DisplayName("records both intentional admissions for an annotated MCP tool")
    void shouldRecordDoubleChargeWhenRateLimitedAndMcpAdmissionBothBindTheSamePolicy() throws Exception {
        recorder().clear();
        HttpResponse<Buffer> response = await(call("weather.double-charged", new JsonObject()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).contains("double-charge-proof");
        assertThat(recorder().events().stream()
                        .filter(event -> event instanceof RateLimitDecisionCompleted completed
                                && completed.policyName().equals("mcp-double")))
                .hasSize(2);
    }

    private static RateLimitObservationRecorder recorder() {
        return app.<McpExampleComponent>component().rateLimitObservationRecorder();
    }

    private static HttpRequest<Buffer> request(String method, String name) {
        return mcpClient
                .post(app.httpPort(), "127.0.0.1", "/mcp/")
                .putHeader("content-type", "application/json")
                .putHeader("Authorization", "Bearer " + bearerToken)
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", method)
                .putHeader("Mcp-Name", name);
    }

    private static Future<HttpResponse<Buffer>> call(String tool, JsonObject arguments) {
        return call(tool, arguments, null);
    }

    private static Future<HttpResponse<Buffer>> call(String tool, JsonObject arguments, String forwardedFor) {
        HttpRequest<Buffer> request = request("tools/call", tool);
        if (forwardedFor != null) {
            request.putHeader("X-Forwarded-For", forwardedFor);
        }
        return request.sendBuffer(new JsonObject()
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
                                .put("arguments", arguments))
                .toBuffer());
    }

    private static JsonObject testConfig() {
        JsonObject tools = new JsonObject()
                .put("weather.current", new JsonObject().put("policy", "mcp-shared"))
                .put("weather.observed", new JsonObject().put("policy", "mcp-observed"))
                .put("weather.ip", new JsonObject().put("policy", "mcp-ip").put("subject", "IP"))
                .put("weather.double-charged", new JsonObject().put("policy", "mcp-double"));
        JsonObject mcp = new JsonObject()
                .put("enabled", true)
                .put("serverName", "vertique-mcp-example-test")
                .put("serverVersion", "1.0")
                .put("authenticationScheme", "bearerAuth")
                .put("rateLimit", new JsonObject().put("tools", tools));
        JsonObject policies = new JsonObject()
                .put("mcp-shared", policy(2))
                .put("mcp-observed", policy(10))
                .put("mcp-ip", policy(1))
                .put("mcp-double", policy(4));
        return new JsonObject()
                .put(
                        "http",
                        new JsonObject().put("port", 0).put("host", "127.0.0.1").put("idleTimeoutSeconds", 30))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                .put("management", new JsonObject().put("enabled", false))
                .put("mcp", mcp)
                .put("resilience", new JsonObject().put("policies", new JsonObject()))
                .put("rateLimit", new JsonObject().put("enabled", true).put("policies", policies));
    }

    private static JsonObject policy(int capacity) {
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
