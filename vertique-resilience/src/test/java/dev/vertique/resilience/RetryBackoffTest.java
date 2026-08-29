// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for executable {@link RetryBackoff} delay policies. */
class RetryBackoffTest {

    @Test
    @DisplayName("fixed backoff returns its configured delay for every retry count")
    void fixedBackoffReturnsConfiguredDelay() {
        RetryBackoff backoff = RetryBackoff.fixed(25L);

        assertEquals(25L, backoff.delayMs(0));
        assertEquals(25L, backoff.delayMs(3));
    }

    @Test
    @DisplayName("exponential backoff grows and caps its base delay before jitter")
    void exponentialBackoffGrowsAndCapsItsBaseDelay() {
        RetryBackoff backoff = RetryBackoff.exponential(10L, 2.0d, 25L, 0L);

        assertEquals(10L, backoff.delayMs(0));
        assertEquals(20L, backoff.delayMs(1));
        assertEquals(25L, backoff.delayMs(2));
        assertEquals(25L, backoff.delayMs(3));
    }

    @Test
    @DisplayName("exponential backoff truncates fractional milliseconds")
    void exponentialBackoffTruncatesFractionalMilliseconds() {
        RetryBackoff backoff = RetryBackoff.exponential(1L, 1.5d, 10L, 0L);

        assertEquals(1L, backoff.delayMs(1));
    }

    @Test
    @DisplayName("exponential backoff keeps jitter within the configured bound")
    void exponentialBackoffKeepsJitterWithinBound() {
        RetryBackoff backoff = RetryBackoff.exponential(10L, 2.0d, 25L, 4L);

        for (int retryCount = 0; retryCount < 4; retryCount++) {
            long base =
                    switch (retryCount) {
                        case 0 -> 10L;
                        case 1 -> 20L;
                        default -> 25L;
                    };
            long delay = backoff.delayMs(retryCount);
            assertTrue(delay >= base && delay < base + Math.min(base, 4L));
        }
    }

    @Test
    @DisplayName("runtime overload uses the supplied random source for jitter")
    void runtimeOverloadUsesSuppliedRandomSource() {
        RetryBackoff backoff = RetryBackoff.exponential(10L, 2.0d, 25L, 4L);

        assertEquals(13L, backoff.delayMs(0, () -> 0.75d));
    }

    @Test
    @DisplayName("runtime overload rejects invalid random source values")
    void runtimeOverloadRejectsInvalidRandomSourceValues() {
        RetryBackoff backoff = RetryBackoff.exponential(10L, 2.0d, 25L, 4L);

        assertThrows(NullPointerException.class, () -> backoff.delayMs(0, null));
        assertThrows(IllegalArgumentException.class, () -> backoff.delayMs(0, () -> -0.1d));
        assertThrows(IllegalArgumentException.class, () -> backoff.delayMs(0, () -> 1.0d));
        assertThrows(IllegalArgumentException.class, () -> backoff.delayMs(0, () -> Double.NaN));
    }

    @Test
    @DisplayName("zero-jitter exponential backoff is deterministic")
    void zeroJitterExponentialBackoffIsDeterministic() {
        RetryBackoff backoff = RetryBackoff.exponential(10L, 2.0d, 25L, 0L);

        assertEquals(25L, backoff.delayMs(2));
        assertEquals(25L, backoff.delayMs(2));
    }

    @Test
    @DisplayName("custom backoff delegates the validated retry count")
    void customBackoffDelegatesRetryCount() {
        AtomicInteger observedRetryCount = new AtomicInteger(-1);
        RetryBackoff backoff = RetryBackoff.custom(retryCount -> {
            observedRetryCount.set(retryCount);
            return 37L;
        });

        assertEquals(37L, backoff.delayMs(4));
        assertEquals(4, observedRetryCount.get());
    }

    @Test
    @DisplayName("all backoff policies reject negative retry counts")
    void backoffsRejectNegativeRetryCounts() {
        assertThrows(
                IllegalArgumentException.class, () -> RetryBackoff.fixed(1L).delayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> RetryBackoff.exponential(1L, 2.0d, 10L)
                .delayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> RetryBackoff.custom(retryCount -> 1L)
                .delayMs(-1));
    }
}
