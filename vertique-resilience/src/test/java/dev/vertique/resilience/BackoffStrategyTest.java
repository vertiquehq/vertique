// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the built-in {@link BackoffStrategy} factories. */
class BackoffStrategyTest {

    @Test
    @DisplayName("fixed() returns a constant delay regardless of retry count")
    void fixedReturnsConstantDelay() {
        BackoffStrategy strategy = BackoffStrategy.fixed(200);

        assertEquals(200L, strategy.delay(0));
        assertEquals(200L, strategy.delay(1));
        assertEquals(200L, strategy.delay(5));
        assertEquals(200L, strategy.delay(100));
    }

    @Test
    @DisplayName("none() returns zero delay for all retry counts")
    void noneReturnsZeroDelay() {
        BackoffStrategy strategy = BackoffStrategy.none();

        assertEquals(0L, strategy.delay(0));
        assertEquals(0L, strategy.delay(1));
        assertEquals(0L, strategy.delay(10));
    }

    @Test
    @DisplayName("exponential() is capped before jitter")
    void exponentialIsCappedBeforeJitter() {
        BackoffStrategy strategy = BackoffStrategy.exponential(100, 2.0, 1_000);

        assertTrue(strategy.delay(0) >= 100 && strategy.delay(0) < 200);
        assertTrue(strategy.delay(1) >= 200 && strategy.delay(1) < 400);
        assertTrue(strategy.delay(2) >= 400 && strategy.delay(2) < 800);
        assertTrue(strategy.delay(5) >= 1_000 && strategy.delay(5) < 2_000);
    }

    @Test
    @DisplayName("exponential() with multiplier one keeps the base delay fixed before jitter")
    void exponentialWithMultiplierOneReturnsFixedBaseDelayBeforeJitter() {
        BackoffStrategy strategy = BackoffStrategy.exponential(500, 1.0, 10_000);

        for (int retryCount = 0; retryCount < 10; retryCount++) {
            long delay = strategy.delay(retryCount);
            assertTrue(
                    delay >= 500 && delay < 1_000, "delay(" + retryCount + ") should be in [500, 1000), was " + delay);
        }
    }

    @Test
    @DisplayName("Default is a BackoffStrategy sentinel class")
    void defaultIsBackoffStrategySentinelClass() {
        assertEquals(BackoffStrategy.Default.class, BackoffStrategy.Default.class);
        assertTrue(BackoffStrategy.class.isAssignableFrom(BackoffStrategy.Default.class));
    }
}
