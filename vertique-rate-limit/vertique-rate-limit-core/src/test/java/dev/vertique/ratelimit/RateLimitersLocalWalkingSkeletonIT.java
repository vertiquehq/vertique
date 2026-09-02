// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.junit5.VertxExtension;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-001: the walking-skeleton end-to-end LOCAL decision path — a Dagger component installing
 * {@link dev.vertique.ratelimit.dagger.RateLimitCoreModule} alone, one configured enabled LOCAL
 * TOKEN_BUCKET policy, {@code RateLimiters.limiter(name).acquire(RateLimitKey.of(...))} against
 * the same key: consumption within capacity yields {@code PERMITTED}, past capacity yields {@code
 * QUOTA_EXCEEDED} with a positive {@code retryAfter}.
 *
 * <p>{@link io.vertx.junit5.VertxExtension} owns the injected {@link io.vertx.core.Vertx}
 * instance's lifecycle and closes it after this test, success or failure; this task's {@link
 * RateLimiters} has no {@code close()} yet — a later task's artifact — so there is nothing further
 * to close here.
 */
@ExtendWith(VertxExtension.class)
class RateLimitersLocalWalkingSkeletonIT {

    private static final long CAPACITY = 2L;

    @Test
    void shouldPermitThenExceedQuotaWithoutRefill(io.vertx.core.Vertx vertx) throws Exception {
        RateLimiters rateLimiters = RateLimitersLocalWalkingSkeletonITFixture.create(vertx, CAPACITY);
        RateLimitKey key = RateLimitKey.of("caller-1");

        List<RateLimitDecision> decisions = acquireThreeTimesOnContext(vertx, rateLimiters, key);

        RateLimitDecision first = decisions.get(0);
        RateLimitDecision second = decisions.get(1);
        RateLimitDecision third = decisions.get(2);

        assertThat(first.outcome()).as("first acquire").isEqualTo(RateLimitOutcome.PERMITTED);
        assertThat(first.remaining()).as("first remaining").hasValue(1L);

        assertThat(second.outcome()).as("second acquire").isEqualTo(RateLimitOutcome.PERMITTED);
        assertThat(second.remaining()).as("second remaining").hasValue(0L);

        assertThat(third.outcome()).as("third acquire").isEqualTo(RateLimitOutcome.QUOTA_EXCEEDED);
        assertThat(third.remaining()).as("third remaining").hasValue(0L);
        assertThat(third.retryAfter()).as("third retryAfter").isPresent();
        assertThat(third.retryAfter().orElseThrow())
                .as("third retryAfter value")
                .isGreaterThan(Duration.ZERO);

        for (RecordComponent component : RateLimitDecision.class.getRecordComponents()) {
            assertThat(component.getType().getPackageName())
                    .as("RateLimitDecision.%s type", component.getName())
                    .doesNotStartWith("io.github.bucket4j");
        }
    }

    private static List<RateLimitDecision> acquireThreeTimesOnContext(
            io.vertx.core.Vertx vertx, RateLimiters rateLimiters, RateLimitKey key) throws Exception {
        CompletableFuture<List<RateLimitDecision>> result = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            RateLimiter limiter = rateLimiters.limiter("walking-skeleton");
            limiter.acquire(key)
                    .compose(first -> limiter.acquire(key).map(second -> List.of(first, second)))
                    .compose(firstTwo ->
                            limiter.acquire(key).map(third -> List.of(firstTwo.get(0), firstTwo.get(1), third)))
                    .onComplete(attempt -> {
                        if (attempt.succeeded()) {
                            result.complete(attempt.result());
                        } else {
                            result.completeExceptionally(attempt.cause());
                        }
                    });
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
