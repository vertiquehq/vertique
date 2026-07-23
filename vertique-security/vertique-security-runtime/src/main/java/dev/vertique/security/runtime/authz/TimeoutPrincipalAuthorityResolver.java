// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;

/**
 * {@link PrincipalAuthorityResolver} decorator that bounds every delegate {@link
 * #resolve(PrincipalKey)} call with an operator-configured timeout (PRD identity-002 §14.3 Phase-2
 * Appendix, ADR-0169), so a delegate resolver whose returned {@link Future} never completes cannot
 * hang Mode-2 authorization indefinitely.
 *
 * <p>{@code SecurityAuthzModule} wraps every installed {@link PrincipalAuthorityResolver} in this
 * decorator before handing it to {@link ReconstructedAuthorityResolvingAuthorizer} — every Mode-2
 * resolver is therefore bounded, not just resolvers an application remembers to wrap itself.
 *
 * <p><strong>Timeout mechanics.</strong> Each call races the delegate's returned {@link Future}
 * against a one-shot {@link Vertx#setTimer(long, io.vertx.core.Handler)} deadline local to that
 * single {@link #resolve} invocation — never recurring, never durable, and cancelled the instant
 * the delegate settles (success or failure), per {@code scheduling.md}'s raw-timer discipline. On
 * timeout, the returned {@link Future} fails (never a bare exception), which {@link
 * ReconstructedAuthorityResolvingAuthorizer}'s existing {@code .recover(...)} already maps to
 * {@link dev.vertique.security.authz.AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED} — the same
 * fail-closed path as any other resolver failure.
 *
 * <p><strong>The timeout does not cancel the delegate's underlying work.</strong> This is a
 * {@link Future}-race, not a cooperative cancellation: if the delegate's {@link
 * #resolve(PrincipalKey)} is backed by a blocking store call or an in-flight network request, that
 * work keeps running to completion (or its own eventual failure) after this decorator has already
 * given up on it and failed the caller. Operators whose {@link PrincipalAuthorityResolver}
 * implementation performs I/O should <strong>also</strong> configure a transport-level timeout on
 * that I/O (e.g. a database statement timeout or an HTTP client read timeout) — this decorator only
 * bounds how long <em>authorization</em> waits, not how long the delegate's own work runs.
 */
public final class TimeoutPrincipalAuthorityResolver implements PrincipalAuthorityResolver {

    private final PrincipalAuthorityResolver delegate;
    private final Vertx vertx;
    private final long timeoutMs;

    /**
     * Constructs a {@code TimeoutPrincipalAuthorityResolver} wrapping {@code delegate} with a
     * {@code timeoutMs}-bounded deadline on every {@link #resolve(PrincipalKey)} call.
     *
     * @param delegate  the application-supplied resolver this decorator bounds; must not be
     *                  {@code null}
     * @param vertx     the {@link Vertx} instance used to schedule and cancel the per-call
     *                  deadline timer; must not be {@code null}
     * @param timeoutMs the bound, in milliseconds, on every {@link #resolve(PrincipalKey)} call;
     *                  must be positive
     * @throws NullPointerException     if {@code delegate} or {@code vertx} is {@code null}
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     */
    public TimeoutPrincipalAuthorityResolver(PrincipalAuthorityResolver delegate, Vertx vertx, long timeoutMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, got: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
    }

    /**
     * Delegates to the wrapped resolver, failing the returned {@link Future} if the delegate has
     * not settled within the configured timeout.
     *
     * @param key the durable, opaque principal key to resolve; must not be {@code null}
     * @return a future of the delegate's current {@link AuthorizationClaims}, or a failed future
     *         if the delegate fails, throws synchronously, or does not settle within the
     *         configured timeout
     */
    @Override
    public Future<AuthorizationClaims> resolve(PrincipalKey key) {
        Objects.requireNonNull(key, "key");
        Promise<AuthorizationClaims> promise = Promise.promise();
        // Per-resolve-call deadline: local to this single invocation, cancelled the instant the
        // delegate settles below — not recurring, not durable, not business-visible scheduling
        // (scheduling.md's raw-timer allow-list: implementation-level timing bound to one
        // in-flight unit of work).
        long timerId = vertx.setTimer(
                timeoutMs,
                id -> promise.tryFail("PrincipalAuthorityResolver did not complete within " + timeoutMs
                        + "ms for principal type " + key.type()));
        // Wrapped in an initial succeededFuture().compose(...) so a synchronous throw from the
        // delegate fails the returned Future rather than escaping this method synchronously —
        // mirrors ReconstructedAuthorityResolvingAuthorizer's same convention.
        Future.succeededFuture().compose(v -> delegate.resolve(key)).onComplete(ar -> {
            vertx.cancelTimer(timerId);
            if (ar.succeeded()) {
                promise.tryComplete(ar.result());
            } else {
                promise.tryFail(ar.cause());
            }
        });
        return promise.future();
    }
}
