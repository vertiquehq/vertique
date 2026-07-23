// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BackoffStrategyResolver}.
 */
class BackoffStrategyResolverTest {

    // --- Test BackoffStrategy implementations ---

    /** A custom strategy that always returns 42. */
    public static class FixedFortyTwo implements BackoffStrategy {
        @Override
        public long delay(int retryCount) {
            return 42L;
        }
    }

    /** A custom strategy with no public no-arg constructor. */
    private static class PrivateConstructor implements BackoffStrategy {
        @Override
        public long delay(int retryCount) {
            return 0;
        }
    }

    // --- Annotation proxy helpers ---

    private Retry retryAnnotation(
            Class<? extends BackoffStrategy> backoff, long delayMs, double multiplier, long maxDelayMs) {
        return new Retry() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return Retry.class;
            }

            @Override
            public int maxRetries() {
                return 3;
            }

            @Override
            public long delayMs() {
                return delayMs;
            }

            @Override
            public double backoffMultiplier() {
                return multiplier;
            }

            @Override
            public long maxDelayMs() {
                return maxDelayMs;
            }

            @Override
            public Class<? extends BackoffStrategy> backoff() {
                return backoff;
            }

            @Override
            public Class<? extends Throwable>[] retryOn() {
                return new Class[0];
            }

            @Override
            public Class<? extends Throwable>[] abortOn() {
                return new Class[0];
            }
        };
    }

    @Test
    @DisplayName("Custom backoff class takes precedence over inline params")
    void shouldUseCustomBackoffClass() {
        Retry retry = retryAnnotation(FixedFortyTwo.class, 500, 2.0, 30_000);
        BackoffStrategy result = BackoffStrategyResolver.resolve(retry, null);
        assertInstanceOf(FixedFortyTwo.class, result);
        assertEquals(42L, result.delay(0));
    }

    @Test
    @DisplayName("Default sentinel with null fallback builds from inline params")
    void shouldBuildFromInlineParamsWhenDefaultAndNoFallback() {
        Retry retry = retryAnnotation(BackoffStrategy.Default.class, 100, 1.0, 10_000);
        BackoffStrategy result = BackoffStrategyResolver.resolve(retry, null);
        assertNotNull(result);
        // Should be exponential with delayMs=100, multiplier=1.0 → base=100, jitter in [0, 100)
        long delay = result.delay(0);
        assertTrue(delay >= 100 && delay < 200, "Expected delay in [100, 200), was " + delay);
    }

    @Test
    @DisplayName("Default sentinel with non-null fallback returns fallback")
    void shouldReturnFallbackWhenDefaultAndFallbackProvided() {
        BackoffStrategy fallback = BackoffStrategy.fixed(999);
        Retry retry = retryAnnotation(BackoffStrategy.Default.class, 100, 2.0, 30_000);
        BackoffStrategy result = BackoffStrategyResolver.resolve(retry, fallback);
        assertSame(fallback, result);
        assertEquals(999L, result.delay(0));
    }

    @Test
    @DisplayName("Invalid custom class throws IllegalStateException")
    void shouldThrowOnInvalidCustomClass() {
        Retry retry = retryAnnotation(PrivateConstructor.class, 100, 2.0, 30_000);
        assertThrows(IllegalStateException.class, () -> BackoffStrategyResolver.resolve(retry, null));
    }
}
