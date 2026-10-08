// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proof of disabled, backend-failure, and defensive admission classification. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionFailurePathsTest {

    private static final String POLICY_NAME = "failure-paths";
    private static final String TOOL_NAME = "failure.probe";
    private static final String BACKEND_FAILURE_MESSAGE = "backend-secret-must-not-escape";

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("disabled engine and disabled policy continue without backend consumption")
    void shouldTreatDisabledEngineAndDisabledPolicyAsPermittedWithNoTokenConsumed() throws Exception {
        RecordingBackend disabledEngineBackend = new RecordingBackend();
        McpToolAdmission disabledEngine = admission(
                runtime(disabledEngineBackend, true, false, RateLimitFailureMode.CLOSED), RateLimitSubject.NONE);
        assertThat(await(disabledEngine.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(disabledEngineBackend.invocations()).isZero();

        RecordingBackend disabledPolicyBackend = new RecordingBackend();
        McpToolAdmission disabledPolicy = admission(
                runtime(disabledPolicyBackend, false, true, RateLimitFailureMode.CLOSED), RateLimitSubject.NONE);
        assertThat(await(disabledPolicy.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(disabledPolicyBackend.invocations()).isZero();

        RecordingBackend enabledBackend = new RecordingBackend();
        McpToolAdmission enabled =
                admission(runtime(enabledBackend, true, true, RateLimitFailureMode.CLOSED), RateLimitSubject.NONE);
        assertThat(await(enabled.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(enabledBackend.invocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("open and closed backend failures retain their distinct admission outcomes")
    void shouldDistinguishOpenFromClosedBackendFailureAndNullDecision() throws Exception {
        RecordingBackend openBackend = new RecordingBackend(true);
        McpToolAdmission open =
                admission(runtime(openBackend, true, true, RateLimitFailureMode.OPEN), RateLimitSubject.NONE);
        assertThat(await(open.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.CONTINUE);
        assertThat(openBackend.invocations()).isEqualTo(1);

        RecordingBackend closedBackend = new RecordingBackend(true);
        McpToolAdmission closed =
                admission(runtime(closedBackend, true, true, RateLimitFailureMode.CLOSED), RateLimitSubject.NONE);
        assertThat(await(closed.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.REJECTED);
        assertThat(closedBackend.invocations()).isEqualTo(1);

        RateLimiter mockedLimiter = mock(RateLimiter.class);
        RateLimitAdapterSupport mockedSupport = mock(RateLimitAdapterSupport.class);
        RateLimiters mockedRuntime = mock(RateLimiters.class);
        when(mockedRuntime.adapterSupport()).thenReturn(mockedSupport);
        when(mockedSupport.limiter(POLICY_NAME)).thenReturn(mockedLimiter);
        when(mockedLimiter.capacity()).thenReturn(10L);
        when(mockedSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(RateLimitKey.global()));
        when(mockedLimiter.acquire(any(RateLimitKey.class), anyLong())).thenReturn(Future.succeededFuture(null));

        McpToolAdmission nullDecision = admission(mockedRuntime, RateLimitSubject.NONE);
        assertThat(await(nullDecision.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.FAILED);
    }

    @Test
    @DisplayName("failed acquire futures and generic key failures fail closed without disclosure")
    void shouldFailClosedForGenericSynchronousKeyDerivationRuntimeException() throws Exception {
        RateLimiter failedLimiter = mock(RateLimiter.class);
        RateLimitAdapterSupport failedFutureSupport = mock(RateLimitAdapterSupport.class);
        RateLimiters failedFutureRuntime = mock(RateLimiters.class);
        when(failedFutureRuntime.adapterSupport()).thenReturn(failedFutureSupport);
        when(failedFutureSupport.limiter(POLICY_NAME)).thenReturn(failedLimiter);
        when(failedLimiter.capacity()).thenReturn(10L);
        when(failedFutureSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(RateLimitKey.global()));
        when(failedLimiter.acquire(any(RateLimitKey.class), anyLong()))
                .thenReturn(Future.failedFuture(new IllegalStateException(BACKEND_FAILURE_MESSAGE)));

        McpToolAdmission failedFuture = admission(failedFutureRuntime, RateLimitSubject.NONE);
        assertThat(await(failedFuture.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.FAILED);

        RateLimitAdapterSupport throwingSupport = mock(RateLimitAdapterSupport.class);
        RateLimiters throwingRuntime = mock(RateLimiters.class);
        RateLimiter neverUsedLimiter = mock(RateLimiter.class);
        when(throwingRuntime.adapterSupport()).thenReturn(throwingSupport);
        when(throwingSupport.limiter(POLICY_NAME)).thenReturn(neverUsedLimiter);
        when(neverUsedLimiter.capacity()).thenReturn(10L);
        when(throwingSupport.subjectKey(any(), any(), any())).thenThrow(new RuntimeException(BACKEND_FAILURE_MESSAGE));

        McpToolAdmission throwing = admission(throwingRuntime, RateLimitSubject.NONE);
        assertThat(await(throwing.admit(TOOL_NAME)).outcome()).isEqualTo(McpToolAdmission.Outcome.FAILED);
        verify(neverUsedLimiter, org.mockito.Mockito.never()).acquire(any(RateLimitKey.class), anyLong());
    }

    private McpToolAdmission admission(RateLimiters rateLimiters, RateLimitSubject subject) {
        McpRateLimitConfig config =
                new McpRateLimitConfig(POLICY_NAME, subject, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of());
        return McpToolAdmission.create(
                McpServerConfig.builder().rateLimit(config).build(),
                McpToolRegistry.build(Set.of(toolInvoker())),
                Optional.of(rateLimiters));
    }

    private RateLimiters runtime(
            RecordingBackend backend,
            boolean policyEnabled,
            boolean rateLimitEnabled,
            RateLimitFailureMode failureMode) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                policyEnabled,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        Map<RateLimitMode, RateLimitBackend> backends = policyEnabled ? Map.of(RateLimitMode.LOCAL, backend) : Map.of();
        return new RateLimiters(Set.of(policy), backends, null, vertx, Set.of(), Optional::empty, rateLimitEnabled);
    }

    private static McpToolInvoker toolInvoker() {
        return new McpToolInvoker() {
            private final McpToolDescriptor descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "Failure-path fixture.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\",\"additionalProperties\":false}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public dev.vertique.mcp.tool.McpPreparedToolCall prepare(
                    Map<String, Object> arguments, dev.vertique.mcp.tool.McpCancellationSignal cancellation) {
                throw new AssertionError("Only exercises admission");
            }
        };
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class RecordingBackend implements RateLimitBackend {
        private final AtomicInteger invocations = new AtomicInteger();
        private final boolean fail;

        private RecordingBackend() {
            this(false);
        }

        private RecordingBackend(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            invocations.incrementAndGet();
            if (fail) {
                return Future.failedFuture(new IllegalStateException(BACKEND_FAILURE_MESSAGE));
            }
            return Future.succeededFuture(new RateLimitBackendResult(
                    true,
                    request.algorithm().capacity() - request.cost(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
        }

        int invocations() {
            return invocations.get();
        }
    }
}
