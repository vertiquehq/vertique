// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static dev.vertique.resilience.ResilienceTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof that the builders that gained options with the cancellation and retry hint work are still
 * single use: once built, a builder refuses further configuration instead of silently ignoring it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BuilderSingleUseTest {

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    /** The circuit breaker builder refuses a failure predicate after it was built. */
    @Test
    @DisplayName("a built circuit breaker builder refuses a failure predicate")
    void builtCircuitBreakerBuilderRefusesAFailurePredicate(Vertx vertx) {
        // Given a circuit breaker builder that has built its breaker
        resilience = Resilience.create(vertx);
        CircuitBreaker.Builder builder = CircuitBreaker.builder(resilience, "single-use-breaker");
        builder.build();

        // When a failure predicate is set afterwards
        // Then the builder refuses it
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> builder.countsAsFailure(failure -> true));
        assertEquals("circuit-breaker builder has already been used", refused.getMessage());
    }

    /** The retry builder chains its hint maximum and refuses it after it was built. */
    @Test
    @DisplayName("the retry builder chains its hint maximum and refuses it once built")
    void retryBuilderChainsItsHintMaximumAndRefusesItOnceBuilt(Vertx vertx) {
        // Given a retry builder
        resilience = Resilience.create(vertx);
        Retry.Builder builder = Retry.builder(resilience, "single-use-retry");

        // When the hint maximum is set
        // Then the same builder is returned for chaining
        assertSame(builder, builder.retryAfterMaxMs(250L));

        // And once the retry is built the builder refuses the hint maximum
        builder.build();
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> builder.retryAfterMaxMs(500L));
        assertEquals("retry builder has already been used", refused.getMessage());
    }

    /** The retry configuration builder refuses the hint maximum after it was built. */
    @Test
    @DisplayName("a built retry configuration builder refuses a hint maximum")
    void builtRetryConfigurationBuilderRefusesAHintMaximum() {
        // Given a retry configuration builder that has built its configuration
        RetryConfig.Builder builder = RetryConfig.builder();
        builder.build();

        // When the hint maximum is set afterwards
        // Then the builder refuses it
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> builder.retryAfterMaxMs(500L));
        assertEquals("retry config builder has already been used", refused.getMessage());
    }
}
