// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
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
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T005 proof that a disconnect suppresses a late admission completion. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionDisconnectIT {

    private static final String POLICY_NAME = "admission-disconnect";
    private static final String TOOL_NAME = "admission.walkingSkeleton";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String LOOPBACK = "127.0.0.1";
    private static final long TIMEOUT_SECONDS = 10;

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
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void shouldSuppressLateAdmissionCompletionAfterDisconnectWithExactlyOneTerminalAndCompletion() throws Exception {
        PendingRateLimitBackend backend = new PendingRateLimitBackend();
        RecordingLifecycleObserver observer = new RecordingLifecycleObserver();
        RateLimiters rateLimiters = rateLimiters(backend);
        fixture = McpToolAdmissionWalkingSkeletonIT.McpToolAdmissionWalkingSkeletonFixture.start(
                vertx,
                new McpRateLimitConfig(
                        POLICY_NAME, RateLimitSubject.NONE, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()),
                Optional.of(rateLimiters),
                observer);
        rawClient = vertx.createHttpClient();

        WebClient.wrap(rawClient)
                .post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(callBody())
                .onComplete(ignored -> {});

        assertThat(backend.awaitInvoked()).isTrue();
        await(rawClient.close());
        rawClient = null;

        assertThat(observer.awaitSettlement())
                .as("the disconnected request must settle while admission is still pending")
                .isTrue();
        observer.assertExactlyOneTerminalThenOneCompletion();

        backend.release();
        drainRequestContext(observer.expectedContext());

        assertThat(fixture.toolInvocationCount())
                .as("the tool handler must never run after disconnect during admission")
                .isZero();
        observer.assertExactlyOneTerminalThenOneCompletion();
    }

    private RateLimiters rateLimiters(PendingRateLimitBackend backend) {
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

    private static Buffer callBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put("_meta", meta)
                                .put("name", TOOL_NAME)
                                .put("arguments", new JsonObject()))
                .toBuffer();
    }

    private static void drainRequestContext(Context requestContext) throws Exception {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        requestContext.runOnContext(ignored -> marker.complete(null));
        marker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class PendingRateLimitBackend implements RateLimitBackend {
        private final Promise<RateLimitBackendResult> result = Promise.promise();
        private final CountDownLatch invoked = new CountDownLatch(1);

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            invoked.countDown();
            return result.future();
        }

        boolean awaitInvoked() throws InterruptedException {
            return invoked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void release() {
            result.complete(new RateLimitBackendResult(
                    true, requestCapacity(), Optional.empty(), Optional.empty(), Optional.empty()));
        }

        private long requestCapacity() {
            return 9L;
        }
    }

    private static final class RecordingLifecycleObserver implements McpRequestLifecycleObserver {
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completedCount = new AtomicInteger();
        private volatile Context expectedContext;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            expectedContext = Vertx.currentContext();
            return new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    terminalCount.incrementAndGet();
                    order.add("terminal");
                    settlement.countDown();
                }

                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    completedCount.incrementAndGet();
                    order.add("completed");
                    settlement.countDown();
                }
            };
        }

        boolean awaitSettlement() throws InterruptedException {
            return settlement.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        Context expectedContext() {
            return expectedContext;
        }

        void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount.get()).isOne();
            assertThat(completedCount.get()).isOne();
            assertThat(order).containsExactly("terminal", "completed");
        }
    }
}
