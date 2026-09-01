// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * TP-004: {@link GreedyRateLimitRefill} and {@link IntervalRateLimitRefill} must map to genuinely
 * distinct native Bucket4j refill behavior, not just distinct labels
 * (contracts/rate-limit-runtime.md, "Bucket4j translation contract" — {@code refillGreedy} vs
 * {@code refillIntervally}). Uses an injected deterministic {@link TimeMeter}; no wall-clock
 * sleep, per repository convention for refill-timing proofs.
 */
class RateLimitAlgorithmTranslationTest {

    private static final long CAPACITY = 10L;
    private static final Duration PERIOD = Duration.ofSeconds(1);

    @Test
    void shouldMapGreedyAndIntervalRefillToDistinctBucket4jBandwidthBehavior() {
        MutableTimeMeter clock = new MutableTimeMeter();
        TokenBucketRateLimit greedyAlgorithm =
                new TokenBucketRateLimit(CAPACITY, new GreedyRateLimitRefill(CAPACITY, PERIOD));
        TokenBucketRateLimit intervalAlgorithm =
                new TokenBucketRateLimit(CAPACITY, new IntervalRateLimitRefill(CAPACITY, PERIOD));
        Bucket greedyBucket = toBucket(greedyAlgorithm, clock);
        Bucket intervalBucket = toBucket(intervalAlgorithm, clock);

        assertThat(greedyBucket.tryConsumeAsMuchAsPossible())
                .as("greedy bucket starts full and is drained at time zero")
                .isEqualTo(CAPACITY);
        assertThat(intervalBucket.tryConsumeAsMuchAsPossible())
                .as("interval bucket starts full and is drained at time zero")
                .isEqualTo(CAPACITY);

        clock.advance(PERIOD.dividedBy(2));

        boolean greedyPermitsMidPeriod = greedyBucket.tryConsume(1L);
        boolean intervalPermitsMidPeriod = intervalBucket.tryConsume(1L);

        assertThat(greedyPermitsMidPeriod)
                .as("greedy refill accrues fractional progress already available mid-period")
                .isTrue();
        assertThat(intervalPermitsMidPeriod)
                .as("interval refill adds no tokens until the full period boundary")
                .isFalse();
    }

    /** Same recipe {@code LocalBucket4jRateLimitBackend} uses, parameterized by an injected clock. */
    private static Bucket toBucket(TokenBucketRateLimit algorithm, TimeMeter clock) {
        RateLimitRefill refill = algorithm.refill();
        Bandwidth bandwidth =
                switch (refill) {
                    case GreedyRateLimitRefill greedy ->
                        Bandwidth.builder()
                                .capacity(algorithm.capacity())
                                .refillGreedy(greedy.tokens(), greedy.period())
                                .build();
                    case IntervalRateLimitRefill interval ->
                        Bandwidth.builder()
                                .capacity(algorithm.capacity())
                                .refillIntervally(interval.tokens(), interval.period())
                                .build();
                };
        return Bucket.builder()
                .addLimit(bandwidth)
                .withCustomTimePrecision(clock)
                .build();
    }

    /** Deterministic, manually-advanced {@link TimeMeter} — no wall-clock sleep. */
    private static final class MutableTimeMeter implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
