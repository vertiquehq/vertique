// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link TimeoutPrincipalAuthorityResolver} — the Mode-2 decorator that bounds every
 * {@link PrincipalAuthorityResolver#resolve} call with an operator-configured timeout (PRD
 * identity-002 §14.3 Phase-2 Appendix, ADR-0169). Verifies the timeout actually fires (a
 * never-completing delegate no longer hangs Mode-2 authorization indefinitely), that a fast result
 * and a fast failure both pass through unchanged, and that the per-call deadline timer is cancelled
 * once the delegate settles (no timer leak — {@code scheduling.md}'s cancellation discipline).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TimeoutPrincipalAuthorityResolverTest {

    private static final PrincipalKey KEY = new PrincipalKey(PrincipalType.USER, "user-1");
    private static final AuthorizationClaims CLAIMS = new AuthorizationClaims(
            Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "resolved", Map.of())), Map.of());

    @Test
    @DisplayName("a delegate that never completes fails the returned future once the configured timeout elapses")
    void timesOutToFailedFuture(Vertx vertx, VertxTestContext testCtx) {
        PrincipalAuthorityResolver neverCompletes =
                key -> Promise.<AuthorizationClaims>promise().future();
        TimeoutPrincipalAuthorityResolver resolver = new TimeoutPrincipalAuthorityResolver(neverCompletes, vertx, 50L);

        resolver.resolve(KEY)
                .onComplete(testCtx.failing(t -> testCtx.verify(() -> {
                    assertTrue(
                            t.getMessage() != null && t.getMessage().contains("50"),
                            "the failure message should name the configured timeout: " + t.getMessage());
                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("a delegate that completes within the timeout window passes its result through unchanged, and "
            + "the deadline timer is cancelled once the delegate settles (no leak)")
    void passesThroughFastResult(Vertx vertx, VertxTestContext testCtx) {
        Vertx spyVertx = spy(vertx);
        // Let the real timer still be scheduled (so a genuinely leaked timer would still be
        // observable), while making the scheduling itself inspectable via the spy.
        doAnswer(inv -> inv.callRealMethod()).when(spyVertx).setTimer(anyLong(), any());

        PrincipalAuthorityResolver fast = key -> Future.succeededFuture(CLAIMS);
        TimeoutPrincipalAuthorityResolver resolver = new TimeoutPrincipalAuthorityResolver(fast, spyVertx, 5_000L);

        resolver.resolve(KEY)
                .onComplete(testCtx.succeeding(claims -> testCtx.verify(() -> {
                    assertEquals(CLAIMS, claims, "the delegate's result must pass through unchanged");
                    // Proves the deadline timer is actually cancelled on delegate completion, not merely
                    // rendered harmless by Promise idempotency — a regression here would leak a live timer
                    // per resolve() call, per scheduling.md's cancellation discipline.
                    verify(spyVertx).cancelTimer(anyLong());
                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("a delegate that fails fast passes its failure through unchanged")
    void passesThroughFastFailure(Vertx vertx, VertxTestContext testCtx) {
        RuntimeException cause = new RuntimeException("boom");
        PrincipalAuthorityResolver failingFast = key -> Future.failedFuture(cause);
        TimeoutPrincipalAuthorityResolver resolver = new TimeoutPrincipalAuthorityResolver(failingFast, vertx, 5_000L);

        resolver.resolve(KEY)
                .onComplete(testCtx.failing(t -> testCtx.verify(() -> {
                    assertSame(
                            cause, t, "the delegate's failure must pass through unchanged, not be replaced or wrapped");
                    testCtx.completeNow();
                })));
    }
}
