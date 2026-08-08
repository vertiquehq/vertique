// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Shared helpers for the JWT auth test classes in this package.
 *
 * <p>Holds three idioms that were previously repeated across {@code JwtValidationLeewayTest},
 * {@code RefreshableJwtAuthTest}, and {@code RefreshableJwtAuthIT}: minting time-claim offsets,
 * the authenticate-then-settle-the-{@link VertxTestContext} tail, and the recursive Vert.x-timer
 * poll gate the integration test uses to wait for an asynchronous condition.
 *
 * <p>The success assertion here is deliberately the <em>strongest</em> form any caller previously
 * used — it also asserts the authenticated {@link io.vertx.ext.auth.User} is non-null — so folding
 * the variants together cannot weaken a check.
 */
final class JwtAuthTestSupport {

    /** How long {@link #awaitReady} waits before failing the test. */
    private static final long GATE_TIMEOUT_MILLIS = 10_000;

    /** How often {@link #awaitReady} re-evaluates its readiness check. */
    private static final long GATE_POLL_INTERVAL_MILLIS = 50;

    private JwtAuthTestSupport() {
        // static helpers only
    }

    /**
     * Returns the epoch-second value {@code offsetSeconds} away from now (negative = in the past).
     *
     * @param offsetSeconds the offset from now, in seconds
     * @return the resulting epoch-second value
     */
    static long secondsFromNow(long offsetSeconds) {
        return Instant.now().getEpochSecond() + offsetSeconds;
    }

    /**
     * Authenticates {@code token} and completes {@code testContext} when it is accepted; fails the
     * context with the cause otherwise.
     *
     * <p>Asserts on the authentication <em>outcome</em> only — never on the failure message or
     * exception type. Vert.x reports expired-, not-yet-valid-, and issued-in-the-future rejections
     * with the identical message {@code "Invalid JWT token: token expired."}, so those carry no
     * discriminating information.
     *
     * @param auth        the provider under test
     * @param token       the token to authenticate
     * @param testContext the context to settle
     */
    static void assertAuthenticationSucceeds(JWTAuth auth, String token, VertxTestContext testContext) {
        assertAuthenticationSucceeds(auth, token, testContext, () -> {});
    }

    /**
     * Authenticates {@code token} and closes {@code auth} as soon as the attempt settles — on both
     * the accepted and the rejected path — before settling {@code testContext} exactly as
     * {@link #assertAuthenticationSucceeds(JWTAuth, String, VertxTestContext)} does.
     *
     * <p>Closing on <em>every</em> exit path is what keeps a failing assertion from also leaking the
     * refresh timer.
     *
     * @param auth        the refreshing provider under test
     * @param token       the token to authenticate
     * @param testContext the context to settle
     */
    static void closeThenAssertAuthenticationSucceeds(
            RefreshableJwtAuth auth, String token, VertxTestContext testContext) {
        assertAuthenticationSucceeds(auth, token, testContext, auth::close);
    }

    /**
     * Completes {@code testContext} when authentication fails; fails it when the token is accepted.
     *
     * @param auth        the provider under test
     * @param token       the token that must be rejected
     * @param testContext the context to settle
     */
    static void assertAuthenticationFails(JWTAuth auth, String token, VertxTestContext testContext) {
        auth.authenticate(new TokenCredentials(token)).onComplete(result -> {
            if (result.failed()) {
                testContext.completeNow();
            } else {
                testContext.failNow(new AssertionError("Expected authentication to fail, but it succeeded"));
            }
        });
    }

    /**
     * Polls {@code readiness} until it yields {@code true}, then runs {@code onReady}. Re-schedules
     * itself on the Vert.x timer rather than blocking a thread, so the poll never occupies the event
     * loop between checks. Gives up after {@link #GATE_TIMEOUT_MILLIS}, which stays well inside the
     * calling class's timeout so a stalled condition reports as an assertion failure rather than a
     * hang.
     *
     * <p>{@code readiness} is asynchronous because one caller's condition is itself an authentication
     * round-trip; a synchronous check simply returns a completed future. A {@code readiness} future
     * that <em>fails</em> counts as "not ready yet" and is retried.
     *
     * <p>{@code timeoutError} is a supplier rather than a fixed message so each gate can report its
     * own diagnostic — the JWKS gate names the request count it observed, which a shared message
     * would lose.
     *
     * @param vertx        the Vert.x instance used to schedule the poll
     * @param readiness    evaluated on every poll; {@code true} means the condition has been met
     * @param timeoutError builds the {@link AssertionError} reported once the deadline passes
     * @param onReady      run once the condition is met
     * @param onTimeout    invoked with {@code timeoutError}'s value if the condition is never met
     */
    static void awaitReady(
            Vertx vertx,
            Supplier<Future<Boolean>> readiness,
            Supplier<AssertionError> timeoutError,
            Runnable onReady,
            Handler<Throwable> onTimeout) {
        awaitReady(
                vertx, readiness, timeoutError, System.currentTimeMillis() + GATE_TIMEOUT_MILLIS, onReady, onTimeout);
    }

    /**
     * Recursive body of the gate above.
     *
     * @param vertx        the Vert.x instance used to schedule the poll
     * @param readiness    evaluated on every poll; {@code true} means the condition has been met
     * @param timeoutError builds the {@link AssertionError} reported once the deadline passes
     * @param deadline     the absolute {@link System#currentTimeMillis()} value at which to give up
     * @param onReady      run once the condition is met
     * @param onTimeout    invoked with {@code timeoutError}'s value once the deadline passes
     */
    private static void awaitReady(
            Vertx vertx,
            Supplier<Future<Boolean>> readiness,
            Supplier<AssertionError> timeoutError,
            long deadline,
            Runnable onReady,
            Handler<Throwable> onTimeout) {
        readiness.get().onComplete(result -> {
            if (result.succeeded() && Boolean.TRUE.equals(result.result())) {
                onReady.run();
            } else if (System.currentTimeMillis() >= deadline) {
                onTimeout.handle(timeoutError.get());
            } else {
                vertx.setTimer(
                        GATE_POLL_INTERVAL_MILLIS,
                        ignored -> awaitReady(vertx, readiness, timeoutError, deadline, onReady, onTimeout));
            }
        });
    }

    /**
     * Shared body of the success assertions: settles {@code testContext} on the authentication
     * outcome, running {@code onSettled} first so cleanup happens on both the accepted and the
     * rejected path.
     *
     * @param auth        the provider under test
     * @param token       the token to authenticate
     * @param testContext the context to settle
     * @param onSettled   cleanup to run as soon as the attempt settles, before the assertion
     */
    private static void assertAuthenticationSucceeds(
            JWTAuth auth, String token, VertxTestContext testContext, Runnable onSettled) {
        auth.authenticate(new TokenCredentials(token)).onComplete(result -> {
            onSettled.run();
            if (result.failed()) {
                testContext.failNow(result.cause());
            } else {
                assertNotNull(result.result());
                testContext.completeNow();
            }
        });
    }
}
