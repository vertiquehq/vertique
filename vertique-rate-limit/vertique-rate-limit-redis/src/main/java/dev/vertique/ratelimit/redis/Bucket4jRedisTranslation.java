// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.IntervalRateLimitRefill;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import io.github.bucket4j.Bandwidth;
import java.math.BigInteger;

/**
 * Pure-function translation from the public {@link TokenBucketRateLimit} shape to Bucket4j's
 * {@link Bandwidth}, and the worst-case time-to-full computation the Vertique-issued {@code
 * PEXPIRE} bound uses (contracts/rate-limit-runtime.md, "Bucket4j translation contract", "Redis
 * integration contract").
 *
 * <p>Duplicated in shape from {@code vertique-rate-limit-core}'s equivalent (package-private,
 * un-exported there — {@code spec.md} §14 defers a shared extraction) rather than reused across
 * the module boundary. Package-private; {@link Bucket4jRedisRateLimitBackend} is this class's only
 * caller.
 */
final class Bucket4jRedisTranslation {

    private Bucket4jRedisTranslation() {}

    /** Builds the one {@link Bandwidth} this module's translation contract produces. */
    static Bandwidth toBandwidth(TokenBucketRateLimit algorithm) {
        var stage = Bandwidth.builder().capacity(algorithm.capacity());
        return switch (algorithm.refill()) {
            case GreedyRateLimitRefill greedy ->
                stage.refillGreedy(greedy.tokens(), greedy.period()).build();
            case IntervalRateLimitRefill interval ->
                stage.refillIntervally(interval.tokens(), interval.period()).build();
        };
    }

    /**
     * Worst-case time, in milliseconds, for this algorithm to refill a bucket from empty back to
     * full — greedy: {@code ceil(capacity * periodMs / tokens)}; interval: {@code ceil(capacity /
     * tokens)} whole periods. Clamped rather than overflowed for pathological configurations.
     */
    static long worstCaseTimeToFullMs(TokenBucketRateLimit algorithm) {
        long capacity = algorithm.capacity();
        return switch (algorithm.refill()) {
            case GreedyRateLimitRefill greedy ->
                ceilDiv(
                        BigInteger.valueOf(capacity)
                                .multiply(BigInteger.valueOf(greedy.period().toMillis())),
                        BigInteger.valueOf(greedy.tokens()));
            case IntervalRateLimitRefill interval -> {
                long periods = ceilDiv(BigInteger.valueOf(capacity), BigInteger.valueOf(interval.tokens()));
                yield clampToLong(BigInteger.valueOf(periods)
                        .multiply(BigInteger.valueOf(interval.period().toMillis())));
            }
        };
    }

    private static long ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] divRem = numerator.divideAndRemainder(denominator);
        BigInteger quotient = divRem[1].signum() == 0 ? divRem[0] : divRem[0].add(BigInteger.ONE);
        return clampToLong(quotient);
    }

    private static long clampToLong(BigInteger value) {
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE / 2);
        return value.compareTo(max) > 0 ? max.longValueExact() : value.longValueExact();
    }

    /**
     * The Vertique-issued {@code PEXPIRE} bound: {@code worstCaseTimeToFullMs(algorithm) +
     * expirationSlackMs}, clamped rather than overflowed.
     */
    static long ttlMs(TokenBucketRateLimit algorithm, long expirationSlackMs) {
        try {
            return Math.addExact(worstCaseTimeToFullMs(algorithm), Math.max(0L, expirationSlackMs));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE / 2;
        }
    }
}
