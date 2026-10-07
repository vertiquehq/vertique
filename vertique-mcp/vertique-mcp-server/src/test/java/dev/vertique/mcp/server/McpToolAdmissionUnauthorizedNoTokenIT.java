// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proof that registry and authorization rejection precede rate-limit admission. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionUnauthorizedNoTokenIT {

    private static final String POLICY_NAME = "unauthorized-no-token";
    private static final String UNKNOWN_TOOL = "interop.missing";
    private static final String RESTRICTED_TOOL = McpGoClientInteropITFixture.RESTRICTED_TOOL;
    private static final String PUBLIC_TOOL = McpGoClientInteropITFixture.PUBLIC_TOOL;
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private final Vertx vertx = Vertx.vertx();

    private McpGoClientInteropITFixture fixture;
    private HttpClient rawClient;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void shouldConsumeNoTokenForUnknownOrUnauthorizedTools() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        RateLimiters rateLimiters = rateLimiters(backend);
        fixture = await(McpGoClientInteropITFixture.start(
                vertx,
                new McpRateLimitConfig(
                        POLICY_NAME, RateLimitSubject.NONE, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()),
                Optional.of(rateLimiters)));
        rawClient = vertx.createHttpClient();

        HttpResponse<Buffer> unknown = await(callTool(UNKNOWN_TOOL, 1, null));
        HttpResponse<Buffer> unauthorized = await(callTool(RESTRICTED_TOOL, 2, null));

        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unauthorized.statusCode()).isEqualTo(400);
        assertThat(new JsonObject(unknown.bodyAsString()).getJsonObject("error").getInteger("code"))
                .isEqualTo(-32602);
        assertThat(new JsonObject(unauthorized.bodyAsString())
                        .getJsonObject("error")
                        .getInteger("code"))
                .isEqualTo(-32602);
        assertThat(backend.invocations())
                .as("unknown and unauthorized calls must not acquire a token")
                .isZero();

        HttpResponse<Buffer> authorized = await(callTool(PUBLIC_TOOL, 3, McpGoClientInteropITFixture.BEARER_ALICE));
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(backend.invocations())
                .as("a valid authorized call is the non-vacuous control")
                .isEqualTo(1);
    }

    private Future<HttpResponse<Buffer>> callTool(String toolName, int requestId, String bearer) {
        var request = WebClient.wrap(rawClient)
                .post(fixture.server().actualPort(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName);
        if (bearer != null) {
            request.putHeader("Authorization", bearer);
        }
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        Buffer body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("_meta", meta).put("name", toolName))
                .toBuffer();
        return request.sendBuffer(body);
    }

    private RateLimiters rateLimiters(RecordingBackend backend) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.CLOSED,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        return new RateLimiters(
                Set.of(policy), Map.of(RateLimitMode.LOCAL, backend), null, vertx, Set.of(), Optional::empty, true);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class RecordingBackend implements RateLimitBackend {
        private final AtomicInteger invocations = new AtomicInteger();
        private final RateLimitBackend delegate = LocalRateLimitBackendFactory.local(ignored -> 100L, 60_000L);

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            invocations.incrementAndGet();
            return delegate.consume(request);
        }

        int invocations() {
            return invocations.get();
        }
    }
}
