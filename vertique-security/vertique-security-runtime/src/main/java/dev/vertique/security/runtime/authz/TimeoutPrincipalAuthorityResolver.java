// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.TimeoutConfig;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import io.vertx.core.Future;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link PrincipalAuthorityResolver} decorator that bounds every delegate {@link
 * #resolve(PrincipalKey)} call with an operator-configured timeout, so a delegate resolver whose
 * returned {@link Future} never completes cannot hang Mode-2 authorization indefinitely.
 *
 * <p>{@code SecurityAuthzModule} wraps every installed {@link PrincipalAuthorityResolver} in this
 * decorator before handing it to {@link ReconstructedAuthorityResolvingAuthorizer} — every Mode-2
 * resolver is therefore bounded, not just resolvers an application remembers to wrap itself.
 *
 * <p><strong>Timeout mechanics.</strong> The delegate is invoked first, synchronously, so a delegate
 * that throws or returns a {@code null} future fails the returned {@link Future} rather than escaping
 * this method. A future the delegate has already completed is returned as is. A pending one is handed
 * to a timeout-only pipeline of the application's {@link Resilience} runtime, which fails it once the
 * configured deadline elapses, settles on the Vert.x context that called {@link #resolve}, and
 * reports the timed-out execution to any installed resilience observer. On timeout, or once the
 * runtime has closed at application shutdown, the returned {@link Future} fails, which {@link
 * ReconstructedAuthorityResolvingAuthorizer}'s existing {@code .recover(...)} already maps to {@link
 * dev.vertique.security.authz.AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED} — the same fail-closed path
 * as any other resolver failure.
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
@Slf4j
public final class TimeoutPrincipalAuthorityResolver implements PrincipalAuthorityResolver {

    /** Resilience identity of the principal-resolution fence. */
    private static final AdapterOperationIdentity PRINCIPAL_RESOLUTION =
            new AdapterOperationIdentity("identity.authz", List.of("principal-resolution"));

    private final PrincipalAuthorityResolver delegate;
    private final ResiliencePipeline fence;

    /**
     * Constructs a {@code TimeoutPrincipalAuthorityResolver} wrapping {@code delegate} with a
     * {@code timeoutMs}-bounded deadline on every {@link #resolve(PrincipalKey)} call.
     *
     * @param delegate   the application-supplied resolver this decorator bounds; must not be
     *                   {@code null}
     * @param resilience the application's resilience runtime, which enforces the deadline; must not
     *                   be {@code null}
     * @param timeoutMs  the bound, in milliseconds, on every {@link #resolve(PrincipalKey)} call;
     *                   must be positive
     * @throws NullPointerException     if {@code delegate} or {@code resilience} is {@code null}
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     */
    public TimeoutPrincipalAuthorityResolver(
            PrincipalAuthorityResolver delegate, Resilience resilience, long timeoutMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(resilience, "resilience");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, got: " + timeoutMs);
        }
        this.fence = resilience
                .adapterSupport()
                .pipeline(
                        PRINCIPAL_RESOLUTION,
                        new ResolvedResiliencePolicy(
                                Optional.of(TimeoutConfig.ofMillis(timeoutMs)),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()));
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
        // Wrapped in an initial succeededFuture().compose(...) so a synchronous throw or a null future
        // from the delegate fails the returned Future rather than escaping this method synchronously
        // — mirrors ReconstructedAuthorityResolvingAuthorizer's same convention.
        Future<AuthorizationClaims> held = Future.succeededFuture().compose(v -> {
            Future<AuthorizationClaims> resolved = delegate.resolve(key);
            return resolved != null
                    ? resolved
                    : Future.<AuthorizationClaims>failedFuture(
                            new IllegalStateException("PrincipalAuthorityResolver returned a null future"));
        });
        if (held.isComplete()) {
            return held;
        }
        Future<AuthorizationClaims> fenced;
        try {
            fenced = fence.execute(() -> held);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return fenced.onFailure(cause -> logFenceFailure(key, cause));
    }

    private void logFenceFailure(PrincipalKey key, Throwable cause) {
        String fenceKey = fence.operationKey();
        if (cause instanceof ResilienceClosedException closed && fenceKey.equals(closed.operationKey())) {
            log.info(
                    "Principal authority resolution for principal type {} was fenced after the resilience runtime closed",
                    key.type());
        } else if (cause instanceof ResilienceTimeoutException timeout && fenceKey.equals(timeout.operationKey())) {
            log.warn(
                    "Principal authority resolution for principal type {} did not complete within {} ms",
                    key.type(),
                    timeout.timeoutMs());
        }
    }
}
