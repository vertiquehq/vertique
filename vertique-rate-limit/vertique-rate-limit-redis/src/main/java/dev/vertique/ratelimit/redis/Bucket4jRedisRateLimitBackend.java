// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.vertx.Bucket4jVertx;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLUSTERED {@link RateLimitBackend}: Bucket4j's Vert.x Redis CAS integration over one shared
 * {@code Redis} client, with Vertique owning the operation deadline, the no-retry policy, and TTL
 * maintenance that {@code bucket4j_jdk17-vertx:8.19.0}'s minimal builder surface has no hook for
 * (contracts/rate-limit-runtime.md, "Redis integration contract").
 *
 * <p>Package-private by design — reached only through {@link #redis}, this class's only
 * production caller ({@code dev.vertique.ratelimit.redis.RateLimitRedisModule}). No Bucket4j or
 * Redis client type appears past this package's own boundary.
 */
final class Bucket4jRedisRateLimitBackend implements RateLimitBackend {

    private static final Logger log = LoggerFactory.getLogger(Bucket4jRedisRateLimitBackend.class);

    private final AsyncProxyManager<String> proxyManager;
    private final RedisAPI ttlClient;
    private final Vertx vertx;
    private final String namespace;
    private final String secret;
    private final long operationTimeoutMs;
    private final long expirationSlackMs;

    Bucket4jRedisRateLimitBackend(
            AsyncProxyManager<String> proxyManager,
            RedisAPI ttlClient,
            Vertx vertx,
            String namespace,
            String secret,
            long operationTimeoutMs,
            long expirationSlackMs) {
        this.proxyManager = Objects.requireNonNull(proxyManager, "proxyManager");
        this.ttlClient = Objects.requireNonNull(ttlClient, "ttlClient");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.secret = Objects.requireNonNull(secret, "secret");
        this.operationTimeoutMs = operationTimeoutMs;
        this.expirationSlackMs = expirationSlackMs;
    }

    /**
     * Builds the CLUSTERED backend from the shared Redis client (contracts/rate-limit-runtime.md,
     * "Redis integration contract" — the exact, frozen construction chain): {@code
     * Bucket4jVertx.casBasedBuilder(sharedRedis).keyMapper(Mapper.STRING).build().asAsync()}. No
     * timeout/retry/TTL/clock hook exists on this builder in 8.19.0 — the deadline, no-retry
     * policy, and TTL maintenance below are all Vertique-owned.
     *
     * @param sharedRedis {@code vertique-redis-core}'s shared client for the configured connection
     *     profile; both the CAS engine and the {@code PEXPIRE} writer issue commands on this same
     *     client, so tests can control every command this backend issues by decorating it
     * @param vertx application Vert.x instance, source of the operation-deadline timer
     * @param namespace {@code rateLimit.redis.namespace}
     * @param secret the resolved {@code rateLimit.keyDerivation.secret}
     * @param operationTimeoutMs {@code rateLimit.redis.operationTimeoutMs}
     * @param expirationSlackMs {@code rateLimit.redis.expirationSlackMs}
     * @return the CLUSTERED {@link RateLimitBackend}
     */
    static RateLimitBackend redis(
            Redis sharedRedis,
            Vertx vertx,
            String namespace,
            String secret,
            long operationTimeoutMs,
            long expirationSlackMs) {
        AsyncProxyManager<String> proxyManager = Bucket4jVertx.casBasedBuilder(sharedRedis)
                .keyMapper(Mapper.STRING)
                .build()
                .asAsync();
        return new Bucket4jRedisRateLimitBackend(
                proxyManager,
                RedisAPI.api(sharedRedis),
                vertx,
                namespace,
                secret,
                operationTimeoutMs,
                expirationSlackMs);
    }

    /**
     * Consumes against the physical Redis key derived from {@code request.storageKey()}, bounded
     * by a Vert.x timer at {@code operationTimeoutMs} — the sole bound on total elapsed time across
     * however many internal CAS attempts Bucket4j's own unbounded retry loop runs. Deadline expiry
     * is classified {@code TIMEOUT}; a Bucket4j completion (success or failure) arriving after the
     * deadline has already fired is discarded, never delivered to the caller.
     */
    @Override
    public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
        String physicalKey = RedisPhysicalKey.physicalKey(namespace, secret, request.storageKey());
        TokenBucketRateLimit algorithm = request.algorithm();
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bucket4jRedisTranslation.toBandwidth(algorithm))
                .build();
        AsyncBucketProxy bucket =
                proxyManager.getProxy(physicalKey, () -> CompletableFuture.completedFuture(configuration));

        Promise<RateLimitBackendResult> promise = Promise.promise();
        AtomicBoolean decided = new AtomicBoolean(false);
        long timerId = vertx.setTimer(Math.max(1L, operationTimeoutMs), ignored -> {
            if (decided.compareAndSet(false, true)) {
                promise.tryComplete(failureResult(RateLimitFailureCode.TIMEOUT));
            }
        });

        bucket.tryConsumeAndReturnRemaining(request.cost()).whenComplete((probe, failure) -> {
            // TTL maintenance runs for any actually-committed write, independent of whether this
            // completion still wins the deadline race below — a late commit is still a real Redis
            // write that must not silently outlive its intended expiry.
            if (failure == null && probe.isConsumed()) {
                issueTtl(physicalKey, algorithm);
            }
            if (!decided.compareAndSet(false, true)) {
                // The operation deadline already fired; this completion (commit, rejection, or
                // failure) is discarded — never delivered to the caller or the observer a second time.
                return;
            }
            vertx.cancelTimer(timerId);
            promise.tryComplete(failure == null ? toResult(probe) : ambiguousFailureResult(failure));
        });

        return promise.future();
    }

    /**
     * Fire-and-forget {@code PEXPIRE} on the shared client, computed the same way the removed
     * {@code ExpirationAfterWriteStrategy} would have been. A failure here is diagnostic-only: it
     * never alters the admission decision already produced by the CAS.
     */
    private void issueTtl(String physicalKey, TokenBucketRateLimit algorithm) {
        long ttlMs = Bucket4jRedisTranslation.ttlMs(algorithm, expirationSlackMs);
        ttlClient
                .pexpire(List.of(physicalKey, Long.toString(ttlMs)))
                .onFailure(cause -> log.warn(
                        "PEXPIRE failed for a committed rate-limit consumption; admission decision unaffected"
                                + " (cause: {})",
                        cause.getClass().getName()));
    }

    private static RateLimitBackendResult toResult(ConsumptionProbe probe) {
        boolean consumed = probe.isConsumed();
        Optional<Duration> retryAfter =
                consumed ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        Optional<Duration> resetAfter = Optional.of(Duration.ofNanos(probe.getNanosToWaitForReset()));
        return new RateLimitBackendResult(
                consumed, probe.getRemainingTokens(), retryAfter, resetAfter, Optional.empty());
    }

    /**
     * An exceptional Bucket4j completion that arrives <em>before</em> the deadline fires is still
     * ambiguous (contracts/rate-limit-runtime.md, "Redis integration contract" — "Timeout,
     * disconnect, malformed state, or other exceptional completion is ambiguous"): this backend has
     * no way to distinguish a dropped connection from a corrupted stored state from the exception
     * shape Bucket4j's Vert.x integration surfaces, so every such failure classifies as {@code
     * UNAVAILABLE} — the most common real-world cause (connectivity) and never {@code
     * CONTENTION_EXHAUSTED} or {@code QUOTA_EXCEEDED}. Never retried by Vertique.
     */
    private static RateLimitBackendResult ambiguousFailureResult(Throwable failure) {
        log.debug(
                "Redis CAS completed exceptionally before the operation deadline (cause: {})",
                failure.getClass().getName());
        return failureResult(RateLimitFailureCode.UNAVAILABLE);
    }

    private static RateLimitBackendResult failureResult(RateLimitFailureCode code) {
        return new RateLimitBackendResult(false, 0L, Optional.empty(), Optional.empty(), Optional.of(code));
    }
}
