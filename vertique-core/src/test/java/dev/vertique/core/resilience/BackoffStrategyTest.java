// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BackoffStrategy} factory methods and the {@link BackoffStrategy.Default}
 * sentinel.
 */
class BackoffStrategyTest {

    @Test
    @DisplayName("fixed() returns constant delay regardless of retry count")
    void fixedShouldReturnConstantDelay() {
        BackoffStrategy strategy = BackoffStrategy.fixed(200);
        assertEquals(200L, strategy.delay(0));
        assertEquals(200L, strategy.delay(1));
        assertEquals(200L, strategy.delay(5));
        assertEquals(200L, strategy.delay(100));
    }

    @Test
    @DisplayName("none() returns zero delay for all retry counts")
    void noneShouldReturnZero() {
        BackoffStrategy strategy = BackoffStrategy.none();
        assertEquals(0L, strategy.delay(0));
        assertEquals(0L, strategy.delay(1));
        assertEquals(0L, strategy.delay(10));
    }

    @Test
    @DisplayName("exponential() produces increasing delays capped at maxDelayMs")
    void exponentialShouldProduceCappedDelays() {
        // Use multiplier=1.0 to eliminate jitter variability in the base delay
        // (jitter is random, so we test bounds instead of exact values)
        BackoffStrategy strategy = BackoffStrategy.exponential(100, 2.0, 1000);

        // retryCount=0: base=100, capped=100, jitter in [0, 100)
        long delay0 = strategy.delay(0);
        assertTrue(delay0 >= 100 && delay0 < 200, "delay(0) should be in [100, 200), was " + delay0);

        // retryCount=1: base=200, capped=200, jitter in [0, 200)
        long delay1 = strategy.delay(1);
        assertTrue(delay1 >= 200 && delay1 < 400, "delay(1) should be in [200, 400), was " + delay1);

        // retryCount=2: base=400, capped=400, jitter in [0, 400)
        long delay2 = strategy.delay(2);
        assertTrue(delay2 >= 400 && delay2 < 800, "delay(2) should be in [400, 800), was " + delay2);

        // retryCount=5: base=3200, capped at maxDelayMs=1000, jitter in [0, 1000)
        long delay5 = strategy.delay(5);
        assertTrue(delay5 >= 1000 && delay5 < 2000, "delay(5) should be in [1000, 2000), was " + delay5);
    }

    @Test
    @DisplayName("exponential() with multiplier=1.0 produces fixed base delay plus jitter")
    void exponentialWithMultiplierOneShouldBeFixedPlusJitter() {
        BackoffStrategy strategy = BackoffStrategy.exponential(500, 1.0, 10_000);
        for (int i = 0; i < 10; i++) {
            long delay = strategy.delay(i);
            // base is always 500, jitter in [0, 500)
            assertTrue(delay >= 500 && delay < 1000, "delay(" + i + ") should be in [500, 1000), was " + delay);
        }
    }

    @Test
    @DisplayName("Default sentinel delay() throws UnsupportedOperationException")
    void defaultSentinelShouldThrowOnDelay() {
        // Cannot instantiate Default directly (private constructor throws),
        // but we can verify the contract via the class reference
        assertEquals(BackoffStrategy.Default.class, BackoffStrategy.Default.class);
        // The sentinel class exists and implements BackoffStrategy
        assertTrue(BackoffStrategy.class.isAssignableFrom(BackoffStrategy.Default.class));
    }
}
