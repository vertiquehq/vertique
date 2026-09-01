// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisAPI;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 8: {@link Bucket4jRedisRateLimitBackend#consume} guards a
 * synchronous throw from consume setup ({@code bucket.tryConsumeAndReturnRemaining} throwing
 * before ever returning a {@link CompletableFuture}) and from {@code issueTtl} (e.g. {@code
 * RedisAPI#pexpire} throwing synchronously instead of returning a failed future) — catching, always
 * cancelling the deadline timer, and settling the result promise exactly once with a classified
 * result, rather than leaving the promise pending until the deadline timer fires (misclassified as
 * {@code TIMEOUT}) or letting the exception propagate straight out of {@code consume()} with the
 * timer left orphaned.
 */
class Bucket4jRedisRateLimitBackendSynchronousThrowGuardTest {

    @Test
    void shouldSettleOnceWithoutHangingUntilDeadlineWhenIssueTtlThrowsSynchronously() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(5, 5, 60_000);
            String storageKey = RateLimitRedisTestFixture.storageKey("sync-throw-pexpire");

            AsyncProxyManager<String> committingProxyManager =
                    proxyManagerReturning(CompletableFuture.completedFuture(ConsumptionProbe.consumed(4L, 0L)));
            RedisAPI throwingTtlClient = mock(RedisAPI.class);
            when(throwingTtlClient.pexpire(anyList())).thenThrow(new RuntimeException("pexpire-sync-throw"));

            // A short-but-bounded deadline: if the guard is missing, the promise only settles once
            // this fires (misclassified TIMEOUT) rather than immediately with the real committed
            // result.
            RateLimitBackend backend = new Bucket4jRedisRateLimitBackend(
                    committingProxyManager,
                    throwingTtlClient,
                    vertx,
                    RateLimitRedisTestFixture.NAMESPACE,
                    RateLimitRedisTestFixture.SECRET,
                    2_000L,
                    1_000L);

            RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                    backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));

            assertThat(result.consumed())
                    .as("the CAS commit itself succeeded; a synchronous PEXPIRE-setup throw must never alter the "
                            + "admission decision already produced by the CAS")
                    .isTrue();
            assertThat(result.failureCode())
                    .as("a synchronous PEXPIRE-setup throw must never surface as a TIMEOUT misclassification")
                    .isEmpty();
        } finally {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    @Test
    void shouldSettleOnceWithClassifiedFailureWhenConsumeSetupThrowsSynchronously() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(5, 5, 60_000);
            String storageKey = RateLimitRedisTestFixture.storageKey("sync-throw-consume-setup");

            AsyncBucketProxy throwingBucketProxy = mock(AsyncBucketProxy.class);
            when(throwingBucketProxy.tryConsumeAndReturnRemaining(anyLong()))
                    .thenThrow(new RuntimeException("consume-setup-sync-throw"));
            @SuppressWarnings("unchecked")
            AsyncProxyManager<String> throwingProxyManager = mock(AsyncProxyManager.class);
            when(throwingProxyManager.getProxy(anyString(), any())).thenReturn(throwingBucketProxy);

            RedisAPI unusedTtlClient = mock(RedisAPI.class);
            RateLimitBackend backend = new Bucket4jRedisRateLimitBackend(
                    throwingProxyManager,
                    unusedTtlClient,
                    vertx,
                    RateLimitRedisTestFixture.NAMESPACE,
                    RateLimitRedisTestFixture.SECRET,
                    30_000L,
                    1_000L);

            RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                    backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));

            assertThat(result.consumed())
                    .as("a synchronous consume-setup throw must never be treated as a successful consumption")
                    .isFalse();
            assertThat(result.failureCode())
                    .as("a synchronous consume-setup throw settles immediately, classified consistently with "
                            + "every other pre-deadline ambiguous failure -- never TIMEOUT")
                    .contains(RateLimitFailureCode.UNAVAILABLE);
        } finally {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    /**
     * External deep-review finding 8 residual: the earlier two guards above only exercise a
     * synchronous throw that happens on the <em>calling</em> thread (a {@code completedFuture}'s
     * {@code whenComplete} lambda runs inline, inside the outer {@code try} that wraps {@code
     * bucket.tryConsumeAndReturnRemaining(...).whenComplete(...)}). This row instead completes the
     * Bucket4j future asynchronously, from a non-caller thread, so the {@code whenComplete} lambda
     * body runs outside that outer {@code try} entirely — exercising the settlement branch's own
     * guard ({@code toResult(probe)}/{@code ambiguousFailureResult(failure)} feeding {@code
     * promise.tryComplete(...)}), not the consume-setup guard. The mocked probe returns normally the
     * first time (so the TTL branch is skipped, exercising the "normal, non-TTL" branch per the
     * contract) and throws the second time — the call {@code toResult} itself makes while mapping
     * the result.
     */
    @Test
    void shouldSettleOnceWithClassifiedFailureWhenResultMappingThrowsOnAsyncCompletion() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(5, 5, 60_000);
            String storageKey = RateLimitRedisTestFixture.storageKey("async-throw-result-mapping");

            ConsumptionProbe throwingProbe = mock(ConsumptionProbe.class);
            when(throwingProbe.isConsumed())
                    .thenReturn(false) // first call: the whenComplete lambda's own TTL-branch check
                    .thenThrow(new RuntimeException("result-mapping-async-throw")); // second call: inside toResult

            CompletableFuture<ConsumptionProbe> asyncOutcome = new CompletableFuture<>();
            AsyncProxyManager<String> committingProxyManager = proxyManagerReturning(asyncOutcome);

            RedisAPI unusedTtlClient = mock(RedisAPI.class);
            // A short-but-bounded deadline: if the settlement branch is unguarded, the exception
            // escapes the async-completion thread uncaught and the promise never settles from this
            // callback at all -- it would only ever resolve via the deadline timer firing, well past
            // this bound.
            RateLimitBackend backend = new Bucket4jRedisRateLimitBackend(
                    committingProxyManager,
                    unusedTtlClient,
                    vertx,
                    RateLimitRedisTestFixture.NAMESPACE,
                    RateLimitRedisTestFixture.SECRET,
                    5_000L,
                    1_000L);

            Future<RateLimitBackendResult> resultFuture =
                    backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1));

            // Complete the Bucket4j future asynchronously, from a thread other than the caller's, well
            // after backend.consume(...) has already returned.
            CompletableFuture.runAsync(() -> asyncOutcome.complete(throwingProbe));

            RateLimitBackendResult result = RateLimitRedisTestFixture.await(resultFuture, 4L);

            assertThat(result.consumed())
                    .as("a synchronous throw while mapping the result must never be treated as a successful "
                            + "consumption")
                    .isFalse();
            assertThat(result.failureCode())
                    .as("a synchronous result-mapping throw on the async-completion thread settles promptly, "
                            + "classified consistently with every other pre-deadline ambiguous failure -- never "
                            + "TIMEOUT")
                    .contains(RateLimitFailureCode.UNAVAILABLE);
        } finally {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    @SuppressWarnings("unchecked")
    private static AsyncProxyManager<String> proxyManagerReturning(CompletableFuture<ConsumptionProbe> outcome) {
        AsyncBucketProxy bucketProxy = mock(AsyncBucketProxy.class);
        when(bucketProxy.tryConsumeAndReturnRemaining(anyLong())).thenReturn(outcome);
        AsyncProxyManager<String> proxyManager = mock(AsyncProxyManager.class);
        when(proxyManager.getProxy(anyString(), any())).thenReturn(bucketProxy);
        return proxyManager;
    }
}
