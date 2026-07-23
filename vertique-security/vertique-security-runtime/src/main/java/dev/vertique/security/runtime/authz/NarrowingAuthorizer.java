// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link Authorizer} decorator that folds an ordered set of {@link AuthorizationNarrower}s over a
 * base authorizer's decision.
 *
 * <p>For each request, the base {@link Authorizer}'s decision is computed first, then each
 * installed {@link AuthorizationNarrower} is given a chance to narrow it further, in
 * {@link dev.vertique.core.extension.OrderedExtension} order (see
 * {@link AuthorizationNarrowerOrdering}). The fold is sequential (async {@code compose}), and each
 * narrower sees the decision produced by the previous step.
 *
 * <p><strong>No-widen guard.</strong> A narrower may only turn a permit into a deny, or annotate an
 * existing deny — it must never turn a deny into a permit. If a narrower's result attempts to widen
 * a current deny into a permit, that result is <strong>discarded</strong>: the current (denied)
 * decision is kept, and a framework-integrity error is logged naming the offending narrower. This
 * fails closed rather than propagating a misbehaving narrower's widened verdict.
 *
 * <p>With an empty narrower set, this decorator is behavior-identical to the base {@link Authorizer}
 * it wraps: every decision passes through unchanged.
 */
@Slf4j
public final class NarrowingAuthorizer implements Authorizer {

    private final Authorizer base;
    private final List<AuthorizationNarrower> orderedNarrowers;

    /**
     * Creates the decorator from the base authorizer and the contributed narrower set.
     *
     * @param base      the wrapped base {@link Authorizer}; must not be {@code null}
     * @param narrowers the contributed narrowers; sorted internally by
     *                  {@link dev.vertique.core.extension.OrderedExtension#comparator()}; must not
     *                  be {@code null}
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if two narrowers share the same {@code (priority, orderKey)} pair
     */
    public NarrowingAuthorizer(Authorizer base, Set<AuthorizationNarrower> narrowers) {
        this.base = Objects.requireNonNull(base, "base");
        this.orderedNarrowers = AuthorizationNarrowerOrdering.sortedAndValidated(narrowers);
    }

    @Override
    public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        // Wrapped in an initial succeededFuture().compose(...) so a synchronous throw from a
        // contract-violating base authorizer or narrower fails the returned Future rather than
        // escaping this method synchronously.
        return Future.succeededFuture()
                .compose(v -> base.authorize(request))
                .compose(baseDecision -> foldNarrowers(request, baseDecision, 0));
    }

    @Override
    public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        if (ctx == null) {
            // Mirrors the base engine: a null context fails closed before any AuthorizationRequest
            // can be built (its compact constructor forbids a null securityContext), so no narrower
            // participates in this outcome.
            return Future.succeededFuture().compose(v -> base.authorize(null, action, resource));
        }
        return authorize(new AuthorizationRequest(ctx, action.value(), resource, Map.of()));
    }

    /**
     * Recursively folds the ordered narrower chain over the running decision.
     *
     * @param request the request being evaluated
     * @param current the decision produced so far (base authorizer, or the previous narrower)
     * @param index   the current position in {@link #orderedNarrowers}
     * @return a future carrying the fully-folded decision
     */
    private Future<AuthorizationDecision> foldNarrowers(
            AuthorizationRequest request, AuthorizationDecision current, int index) {
        if (index >= orderedNarrowers.size()) {
            return Future.succeededFuture(current);
        }
        AuthorizationNarrower narrower = orderedNarrowers.get(index);
        return Future.succeededFuture()
                .compose(v -> narrower.narrow(request, current))
                .compose(candidate ->
                        foldNarrowers(request, applyNoWidenGuard(narrower, current, candidate), index + 1));
    }

    /**
     * Enforces the no-widen invariant: a narrower may not turn a deny into a permit. A violating
     * candidate is discarded (the current decision is kept) and logged as a framework-integrity
     * error.
     *
     * @param narrower  the narrower that produced {@code candidate}
     * @param current   the decision before this narrower ran
     * @param candidate the narrower's proposed decision
     * @return {@code candidate} if it does not widen {@code current}; otherwise {@code current}
     * @throws NullPointerException if {@code candidate} is {@code null}
     */
    private static AuthorizationDecision applyNoWidenGuard(
            AuthorizationNarrower narrower, AuthorizationDecision current, AuthorizationDecision candidate) {
        Objects.requireNonNull(
                candidate, "AuthorizationNarrower " + narrower.getClass().getName() + " returned a null decision");
        if (!current.permitted() && candidate.permitted()) {
            log.error(
                    "AuthorizationNarrower {} attempted to widen a DENY decision (reasonCode={}) into a PERMIT; "
                            + "discarding the widen attempt and keeping the current DENY — a narrower must only "
                            + "narrow, never widen (framework-integrity violation)",
                    narrower.getClass().getName(),
                    current.reasonCode());
            return current;
        }
        return candidate;
    }
}
