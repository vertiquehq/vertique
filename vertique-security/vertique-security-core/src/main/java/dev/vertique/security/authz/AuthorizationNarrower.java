// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.security.SecurityContext;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * SPI that composes on top of the base {@link Authorizer} / {@link AuthorizationIntrospector} pair
 * to apply an additional, narrower constraint — e.g. a delegation-scoped restriction — without
 * touching the base role/policy engine.
 *
 * <p>A narrower participates in two independent surfaces:
 * <ul>
 *   <li>{@link #narrow(AuthorizationRequest, AuthorizationDecision)} — the per-request, async
 *       decision path. Given the request and the decision so far (the base {@link Authorizer}'s
 *       verdict, or a previous narrower's result), it may turn a permit into a deny, or annotate an
 *       existing deny, but it <strong>MUST NOT</strong> turn a deny into a permit. The composition
 *       runner (e.g. {@code NarrowingAuthorizer} in {@code vertique-security-runtime}) enforces this
 *       no-widen invariant: a widen attempt is discarded and logged as a framework-integrity error
 *       rather than honored.</li>
 *   <li>{@link #requirementFor(SecurityContext, ActionRef)} — the per-action, sync introspection
 *       path. Independent of any specific resource or request, it reports whether this narrower
 *       places an additional, describable condition on the action for the given actor. This backs
 *       capability-style introspection ({@code NarrowingIntrospector}), which <strong>annotates</strong>
 *       a capability with the requirement rather than removing it from the result — the requirement
 *       tells a caller "this capability exists but is conditionally gated", not "you cannot do
 *       this".</li>
 * </ul>
 *
 * <p>Narrowers participate in an {@link OrderedExtension}-ordered chain: multiple installed
 * narrowers are folded in {@link OrderedExtension#comparator()} order (phase, then ascending
 * {@link #priority()}, then {@link #orderKey()}). Two narrowers sharing the same
 * {@code (priority, orderKey)} pair are a configuration error; the composition runner fails loudly
 * at startup naming both conflicting classes.
 *
 * <p>With no narrowers installed, the composed {@code Authorizer} / {@code AuthorizationIntrospector}
 * are behavior-identical to the base engine they wrap.
 */
public interface AuthorizationNarrower extends OrderedExtension {

    /**
     * Narrows the decision so far for the given request.
     *
     * <p>May only turn a permit into a deny, or annotate an existing deny (e.g. via
     * {@link AuthorizationDecision#safeAttributes()} or a different {@link AuthzReasonCodes reason
     * code}); it <strong>MUST NOT</strong> turn a deny into a permit. The composition runner enforces
     * this: a candidate decision that widens a current deny into a permit is discarded and the
     * current deny is kept, with a framework-integrity error logged.
     *
     * @param request the authorization request being evaluated; must not be {@code null}
     * @param base    the decision so far — the base {@link Authorizer}'s decision on the first
     *                narrower in the ordered chain, or the previous narrower's result on subsequent
     *                calls; must not be {@code null}
     * @return a future carrying the narrowed decision; mirrors {@link Authorizer#authorize(AuthorizationRequest)}
     *     in never returning a failed future for a normal deny
     */
    Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base);

    /**
     * Reports the requirement, if any, that this narrower places on the given action for the given
     * actor — independent of any specific resource or request.
     *
     * <p>Used by the introspection surface to annotate a capability with a human/machine-readable
     * description of an additional condition the actor must satisfy (e.g. an active delegation
     * grant) beyond the base role/policy permission. An empty result means this narrower places no
     * additional constraint on the action for this actor.
     *
     * @param ctx    the security context of the actor being introspected; must not be {@code null}
     * @param action the action being described; must not be {@code null}
     * @return the requirement gating this action, or {@link Optional#empty()} if unconstrained; never
     *     {@code null}
     */
    Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action);
}
