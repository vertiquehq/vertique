// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link TimeoutPrincipalAuthorityResolver} — the Mode-2 decorator that bounds every
 * {@link PrincipalAuthorityResolver#resolve} call with an operator-configured timeout through the
 * application's {@link Resilience} runtime. Verifies the timeout actually fires (a never-completing
 * delegate no longer hangs Mode-2 authorization indefinitely) and is reported to resilience
 * observers, that a fast result and a fast failure pass through unchanged and untimed, that a
 * delegate that throws or returns {@code null} fails the future instead of escaping, and that a
 * pending resolution fails once the runtime has closed.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TimeoutPrincipalAuthorityResolverTest {

    private static final PrincipalKey KEY = new PrincipalKey(PrincipalType.USER, "user-1");
    private static final AuthorizationClaims CLAIMS = new AuthorizationClaims(
            Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "resolved", Map.of())), Map.of());

    /** Records every event a {@link Resilience} runtime publishes. */
    private static final class Observed implements ResilienceObserver {
        private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(ResilienceEvent event) {
            events.add(event);
        }

        List<ResilienceOutcomeCategory> completions() {
            return events.stream()
                    .filter(ExecutionCompleted.class::isInstance)
                    .map(e -> ((ExecutionCompleted) e).outcome())
                    .toList();
        }
    }

    @Test
    @DisplayName("a delegate that never completes fails the returned future with a resilience timeout, and the "
            + "timed-out execution is reported to the resilience observer")
    void timesOutToFailedFuture(Vertx vertx, VertxTestContext testCtx) {
        Observed observed = new Observed();
        Resilience resilience = Resilience.create(vertx, Set.of(observed));
        PrincipalAuthorityResolver neverCompletes =
                key -> Promise.<AuthorizationClaims>promise().future();
        TimeoutPrincipalAuthorityResolver resolver =
                new TimeoutPrincipalAuthorityResolver(neverCompletes, resilience, 50L);

        resolver.resolve(KEY)
                .onComplete(testCtx.failing(t -> testCtx.verify(() -> {
                    ResilienceTimeoutException timeout = assertInstanceOf(ResilienceTimeoutException.class, t);
                    assertEquals(50L, timeout.timeoutMs(), "the failure must carry the configured timeout");
                    assertEquals(
                            List.of(ResilienceOutcomeCategory.TIMEOUT),
                            observed.completions(),
                            "the resilience runtime must see exactly one timed-out execution");
                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("a delegate that completes later, within the timeout window, passes its result through")
    void passesThroughResultThatArrivesInTime(Vertx vertx, VertxTestContext testCtx) {
        Observed observed = new Observed();
        Resilience resilience = Resilience.create(vertx, Set.of(observed));
        PrincipalAuthorityResolver slow = key -> {
            Promise<AuthorizationClaims> promise = Promise.promise();
            vertx.setTimer(20L, id -> promise.complete(CLAIMS));
            return promise.future();
        };
        TimeoutPrincipalAuthorityResolver resolver = new TimeoutPrincipalAuthorityResolver(slow, resilience, 5_000L);

        resolver.resolve(KEY)
                .onComplete(testCtx.succeeding(claims -> testCtx.verify(() -> {
                    assertEquals(CLAIMS, claims, "the delegate's result must pass through unchanged");
                    assertEquals(List.of(ResilienceOutcomeCategory.SUCCESS), observed.completions());
                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("a delegate that already completed passes its result through without reaching the runtime")
    void passesThroughFastResultUntimed(Vertx vertx) {
        Observed observed = new Observed();
        Resilience resilience = Resilience.create(vertx, Set.of(observed));
        PrincipalAuthorityResolver fast = key -> Future.succeededFuture(CLAIMS);
        TimeoutPrincipalAuthorityResolver resolver = new TimeoutPrincipalAuthorityResolver(fast, resilience, 5_000L);

        Future<AuthorizationClaims> result = resolver.resolve(KEY);

        assertTrue(result.succeeded(), "an already-complete delegate completes the result synchronously");
        assertEquals(CLAIMS, result.result(), "the delegate's result must pass through unchanged");
        assertTrue(observed.events.isEmpty(), "an already-complete delegate must not be fenced");
    }

    @Test
    @DisplayName("a delegate that fails fast passes its failure through unchanged")
    void passesThroughFastFailure(Vertx vertx) {
        RuntimeException cause = new RuntimeException("boom");
        PrincipalAuthorityResolver failingFast = key -> Future.failedFuture(cause);
        TimeoutPrincipalAuthorityResolver resolver =
                new TimeoutPrincipalAuthorityResolver(failingFast, Resilience.create(vertx), 5_000L);

        Future<AuthorizationClaims> result = resolver.resolve(KEY);

        assertTrue(result.failed());
        assertSame(cause, result.cause(), "the delegate's failure must pass through unchanged, not be wrapped");
    }

    @Test
    @DisplayName("a delegate that throws synchronously fails the returned future instead of escaping")
    void synchronousThrowFailsTheFuture(Vertx vertx) {
        IllegalStateException cause = new IllegalStateException("exploded");
        PrincipalAuthorityResolver throwing = key -> {
            throw cause;
        };
        TimeoutPrincipalAuthorityResolver resolver =
                new TimeoutPrincipalAuthorityResolver(throwing, Resilience.create(vertx), 5_000L);

        Future<AuthorizationClaims> result = resolver.resolve(KEY);

        assertTrue(result.failed(), "a synchronous throw must surface as a failed future");
        assertSame(cause, result.cause());
    }

    @Test
    @DisplayName("a delegate that returns a null future fails the returned future instead of escaping")
    void nullFutureFailsTheFuture(Vertx vertx) {
        PrincipalAuthorityResolver returningNull = key -> null;
        TimeoutPrincipalAuthorityResolver resolver =
                new TimeoutPrincipalAuthorityResolver(returningNull, Resilience.create(vertx), 5_000L);

        Future<AuthorizationClaims> result = resolver.resolve(KEY);

        assertTrue(result.failed(), "a null future must surface as a failed future");
    }

    @Test
    @DisplayName("a resolution still pending when the resilience runtime closes fails at once")
    void pendingResolutionFailsOnceTheRuntimeIsClosed(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        PrincipalAuthorityResolver neverCompletes =
                key -> Promise.<AuthorizationClaims>promise().future();
        TimeoutPrincipalAuthorityResolver resolver =
                new TimeoutPrincipalAuthorityResolver(neverCompletes, resilience, 5_000L);
        resilience.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        resolver.resolve(KEY)
                .onComplete(testCtx.failing(t -> testCtx.verify(() -> {
                    assertInstanceOf(ResilienceClosedException.class, t);
                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("a non-positive timeout is rejected")
    void rejectsNonPositiveTimeout(Vertx vertx) {
        Resilience resilience = Resilience.create(vertx);
        PrincipalAuthorityResolver delegate = key -> Future.succeededFuture(CLAIMS);

        assertThrows(
                IllegalArgumentException.class, () -> new TimeoutPrincipalAuthorityResolver(delegate, resilience, 0L));
    }
}
