// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitAdapterSupport;
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

/** Wire proof for backend-failure and defensive admission classifications. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionFailureResponsesIT {

    private static final String POLICY_NAME = "failure-responses";
    private static final String TOOL_NAME = "admission.walkingSkeleton";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String FAILURE_MESSAGE = "backend-detail-must-not-escape";

    private final Vertx vertx = Vertx.vertx();

    private McpToolAdmissionWalkingSkeletonIT.McpToolAdmissionWalkingSkeletonFixture fixture;
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
    void shouldContinueOnBackendFailureOpenWithoutWritingOrLeakingDetails() throws Exception {
        RecordingFailureBackend backend = new RecordingFailureBackend();
        start(runtime(backend, RateLimitFailureMode.OPEN));

        HttpResponse<Buffer> response = await(callTool(1));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).contains("admitted").doesNotContain(FAILURE_MESSAGE);
        assertThat(backend.invocations()).isEqualTo(1);
        assertThat(fixture.toolInvocationCount()).isEqualTo(1);
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.SUCCESS, McpErrorType.NONE));
    }

    @Test
    void shouldReturn503ForBackendFailureClosedAsRejectedWithoutRetryAfter() throws Exception {
        RecordingFailureBackend backend = new RecordingFailureBackend();
        start(runtime(backend, RateLimitFailureMode.CLOSED));

        HttpResponse<Buffer> response = await(callTool(1));

        assertUnavailable(response);
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(backend.invocations()).isEqualTo(1);
        assertThat(fixture.toolInvocationCount()).isZero();
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.REJECTED, McpErrorType.RATE_LIMIT));
    }

    @Test
    void shouldUseOnlyFixedMessagesForEveryAdmissionFailure() throws Exception {
        RateLimitAdapterSupport failedFutureSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter failedFutureLimiter = mock(RateLimiter.class);
        RateLimiters failedFutureRuntime = mock(RateLimiters.class);
        when(failedFutureRuntime.adapterSupport()).thenReturn(failedFutureSupport);
        when(failedFutureSupport.limiter(POLICY_NAME)).thenReturn(failedFutureLimiter);
        when(failedFutureLimiter.capacity()).thenReturn(10L);
        when(failedFutureSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(RateLimitKey.global()));
        when(failedFutureLimiter.acquire(any(RateLimitKey.class), anyLong()))
                .thenReturn(Future.failedFuture(new IllegalStateException(FAILURE_MESSAGE)));
        start(failedFutureRuntime);
        assertUnavailable(await(callTool(1)));
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.FAILED, McpErrorType.RATE_LIMIT));

        tearDownFixture();

        RateLimitAdapterSupport nullSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter nullLimiter = mock(RateLimiter.class);
        RateLimiters nullRuntime = mock(RateLimiters.class);
        when(nullRuntime.adapterSupport()).thenReturn(nullSupport);
        when(nullSupport.limiter(POLICY_NAME)).thenReturn(nullLimiter);
        when(nullLimiter.capacity()).thenReturn(10L);
        when(nullSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(RateLimitKey.global()));
        when(nullLimiter.acquire(any(RateLimitKey.class), anyLong())).thenReturn(Future.succeededFuture(null));
        start(nullRuntime);
        assertUnavailable(await(callTool(2)));
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.FAILED, McpErrorType.RATE_LIMIT));

        tearDownFixture();

        RateLimitAdapterSupport throwingSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter throwingLimiter = mock(RateLimiter.class);
        RateLimiters throwingRuntime = mock(RateLimiters.class);
        when(throwingRuntime.adapterSupport()).thenReturn(throwingSupport);
        when(throwingSupport.limiter(POLICY_NAME)).thenReturn(throwingLimiter);
        when(throwingLimiter.capacity()).thenReturn(10L);
        when(throwingSupport.subjectKey(any(), any(), any())).thenThrow(new IllegalStateException(FAILURE_MESSAGE));
        start(throwingRuntime);
        HttpResponse<Buffer> response = await(callTool(3));
        assertUnavailable(response);
        assertThat(response.bodyAsString()).doesNotContain(FAILURE_MESSAGE);
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.FAILED, McpErrorType.RATE_LIMIT));
    }

    private void start(RateLimiters rateLimiters) throws Exception {
        fixture = McpToolAdmissionWalkingSkeletonIT.McpToolAdmissionWalkingSkeletonFixture.start(
                vertx,
                new McpRateLimitConfig(
                        POLICY_NAME, RateLimitSubject.NONE, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()),
                Optional.of(rateLimiters));
        rawClient = vertx.createHttpClient();
    }

    private void tearDownFixture() throws Exception {
        Future.join(fixture.server().close(), rawClient.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        fixture = null;
        rawClient = null;
    }

    private Future<HttpResponse<Buffer>> callTool(int requestId) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        Buffer body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("_meta", meta).put("name", TOOL_NAME))
                .toBuffer();
        return WebClient.wrap(rawClient)
                .post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(body);
    }

    private RateLimiters runtime(RateLimitBackend backend, RateLimitFailureMode failureMode) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        return new RateLimiters(
                Set.of(policy), Map.of(RateLimitMode.LOCAL, backend), null, vertx, Set.of(), Optional::empty, true);
    }

    private static void assertUnavailable(HttpResponse<Buffer> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonObject error = new JsonObject(response.bodyAsString()).getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(-32010);
        assertThat(error.getString("message")).isEqualTo("Rate limiting unavailable");
        assertThat(response.bodyAsString()).doesNotContain(FAILURE_MESSAGE, POLICY_NAME);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class RecordingFailureBackend implements RateLimitBackend {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            invocations.incrementAndGet();
            return Future.failedFuture(new IllegalStateException(FAILURE_MESSAGE));
        }

        int invocations() {
            return invocations.get();
        }
    }
}
