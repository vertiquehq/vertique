// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RetryConfig} record validation, default constants, and exponential
 * backoff delay computation.
 */
class RetryConfigTest {

    // --- Default constant tests ---

    @Nested
    @DisplayName("DEFAULT constant")
    class DefaultConstant {

        @Test
        @DisplayName("DEFAULT has maxRetries=3")
        void defaultMaxRetries() {
            assertEquals(3, RetryConfig.DEFAULT.maxRetries());
        }

        @Test
        @DisplayName("DEFAULT has backoffMs=1000")
        void defaultBackoffMs() {
            assertEquals(1000L, RetryConfig.DEFAULT.backoffMs());
        }

        @Test
        @DisplayName("DEFAULT has backoffMultiplier=2.0")
        void defaultBackoffMultiplier() {
            assertEquals(2.0, RetryConfig.DEFAULT.backoffMultiplier());
        }

        @Test
        @DisplayName("DEFAULT has exhaustedStrategy=DEAD_LETTER")
        void defaultExhaustedStrategy() {
            assertEquals(ErrorStrategy.DEAD_LETTER, RetryConfig.DEFAULT.exhaustedStrategy());
        }

        @Test
        @DisplayName("DEFAULT has maxBackoffMs=60000")
        void defaultMaxBackoffMs() {
            assertEquals(60_000L, RetryConfig.DEFAULT.maxBackoffMs());
        }
    }

    // --- delayMs() computation tests ---

    @Nested
    @DisplayName("delayMs() exponential backoff")
    class DelayMs {

        @Test
        @DisplayName("delayMs(0) returns base backoffMs")
        void delayMsAtRetryZeroReturnsBase() {
            RetryConfig config = new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L);
            assertEquals(1000L, config.delayMs(0));
        }

        @Test
        @DisplayName("delayMs(1) returns backoffMs * multiplier")
        void delayMsAtRetryOneReturnsDoubled() {
            RetryConfig config = new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L);
            assertEquals(2000L, config.delayMs(1));
        }

        @Test
        @DisplayName("delayMs(2) returns backoffMs * multiplier^2")
        void delayMsAtRetryTwoReturnsExponentialGrowth() {
            RetryConfig config = new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L);
            assertEquals(4000L, config.delayMs(2));
        }

        @Test
        @DisplayName("delayMs(n) is capped at maxBackoffMs when computed value exceeds it")
        void delayMsIsCappedAtMaxBackoff() {
            // With 1000ms base, 2x multiplier: retry 10 would be 1024000ms, well above cap
            RetryConfig config = new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L);
            assertEquals(60_000L, config.delayMs(10));
        }

        @Test
        @DisplayName("delayMs() with backoffMs=0 always returns 0")
        void delayMsWithZeroBackoffAlwaysReturnsZero() {
            RetryConfig config = new RetryConfig(3, 0L, 2.0, ErrorStrategy.DEAD_LETTER, 0L);
            assertEquals(0L, config.delayMs(0));
            assertEquals(0L, config.delayMs(5));
        }

        @Test
        @DisplayName("delayMs() cap is exact when computed value equals maxBackoffMs")
        void delayMsIsExactlyCapWhenEqualToMax() {
            // 500ms base, 2x multiplier: retry 1 => 1000ms which equals maxBackoffMs
            RetryConfig config = new RetryConfig(3, 500L, 2.0, ErrorStrategy.DEAD_LETTER, 1000L);
            assertEquals(1000L, config.delayMs(1));
        }
    }

    // --- Constructor validation tests ---

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("maxRetries < 1 throws IllegalArgumentException")
        void maxRetriesLessThanOneFails() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RetryConfig(0, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("maxRetries = 1 is accepted")
        void maxRetriesOfOneIsAccepted() {
            assertDoesNotThrow(() -> new RetryConfig(1, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("backoffMs < 0 throws IllegalArgumentException")
        void negativeBackoffMsFails() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RetryConfig(3, -1L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("backoffMs = 0 is accepted")
        void zeroBackoffMsIsAccepted() {
            assertDoesNotThrow(() -> new RetryConfig(3, 0L, 1.0, ErrorStrategy.DEAD_LETTER, 0L));
        }

        @Test
        @DisplayName("backoffMultiplier < 1.0 throws IllegalArgumentException")
        void backoffMultiplierBelowOneFails() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RetryConfig(3, 1000L, 0.9, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("backoffMultiplier = 1.0 is accepted")
        void backoffMultiplierOfOneIsAccepted() {
            assertDoesNotThrow(() -> new RetryConfig(3, 1000L, 1.0, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("exhaustedStrategy = RETRY throws IllegalArgumentException")
        void exhaustedStrategyRetryFails() {
            assertThrows(
                    IllegalArgumentException.class, () -> new RetryConfig(3, 1000L, 2.0, ErrorStrategy.RETRY, 60_000L));
        }

        @Test
        @DisplayName("exhaustedStrategy = SKIP is accepted")
        void exhaustedStrategySkipIsAccepted() {
            assertDoesNotThrow(() -> new RetryConfig(3, 1000L, 2.0, ErrorStrategy.SKIP, 60_000L));
        }

        @Test
        @DisplayName("exhaustedStrategy = DEAD_LETTER is accepted")
        void exhaustedStrategyDeadLetterIsAccepted() {
            assertDoesNotThrow(() -> new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L));
        }

        @Test
        @DisplayName("maxBackoffMs < backoffMs throws IllegalArgumentException")
        void maxBackoffLessThanBackoffMsFails() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 500L));
        }
    }
}
