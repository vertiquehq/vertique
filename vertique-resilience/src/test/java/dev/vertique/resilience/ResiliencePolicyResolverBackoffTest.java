// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** TP-003 proof for complete and partial operation-supplied exponential backoff tuples. */
@DisplayName("ResiliencePolicyResolver backoff")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ResiliencePolicyResolverBackoffTest {

    private static final long AWAIT_TIMEOUT_MS = 5_000L;

    private Vertx vertx;
    private Resilience resilience;

    @AfterEach
    void closeRuntimeAndVertx() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("accepts a complete configured backoff tuple without a declaration")
    void acceptsACompleteConfiguredBackoffTupleWithoutADeclaration() {
        vertx = Vertx.vertx();
        resilience = Resilience.create(vertx);

        ResilienceAnnotations annotations = new ResilienceAnnotations(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("payments"));
        ResilienceDefaults defaults = ResilienceDefaults.none();

        RetryOverride completeRetry = retryOverride(new BackoffOverride(
                Optional.empty(),
                OptionalLong.of(500L),
                Optional.of(2.0d),
                OptionalLong.of(30_000L),
                OptionalLong.empty()));
        ResolvedResiliencePolicy resolved =
                resilience.policyResolver().resolve(annotations, retryOverrides(completeRetry), defaults);

        RetryConfig retry = resolved.retry().orElseThrow();
        assertEquals(5, retry.maxRetries());
        RetryBackoff.Exponential backoff = assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
        assertEquals(500L, backoff.initialDelayMs());
        assertEquals(2.0d, backoff.multiplier());
        assertEquals(30_000L, backoff.maxDelayMs());
        assertEquals(1_000L, backoff.maxJitterMs());

        RetryOverride partialRetry = retryOverride(new BackoffOverride(
                Optional.empty(), OptionalLong.of(500L), Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
        ResiliencePolicyException incomplete = assertThrows(
                ResiliencePolicyException.class,
                () -> resilience.policyResolver().resolve(annotations, retryOverrides(partialRetry), defaults));
        assertEquals("Incomplete resilience policy configuration", incomplete.getMessage());
    }

    private static RetryOverride retryOverride(BackoffOverride backoff) {
        return new RetryOverride(
                Optional.empty(),
                OptionalInt.of(5),
                Optional.of(backoff),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ResiliencePolicyOverrides retryOverrides(RetryOverride retry) {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
