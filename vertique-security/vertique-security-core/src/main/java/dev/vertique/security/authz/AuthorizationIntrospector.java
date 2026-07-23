// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.security.SecurityContext;
import java.util.Set;

/**
 * Reports which registered actions an actor is permitted to perform — the set-valued inverse of a
 * single {@link Authorizer} decision.
 *
 * <p>Where {@link Authorizer#authorize(AuthorizationRequest)} answers "is <em>this</em> action
 * permitted?", an {@code AuthorizationIntrospector} answers "which of the registered actions are
 * permitted for this actor?". It exists to back capability-style endpoints (e.g. a "what can I do"
 * introspection surface) without forcing a caller to probe each action individually.
 *
 * <p><strong>Agreement invariant (non-reconstructed contexts only).</strong> For a
 * <strong>non-reconstructed</strong> {@link SecurityContext} — one whose
 * {@link SecurityContext#reconstruction()} is {@link java.util.Optional#empty()} — an introspector
 * must be consistent with the {@link Authorizer} it shadows: for the same engine state and actor, a
 * registered action is in {@link #allowedActions(SecurityContext)} <em>iff</em> the authorizer permits
 * that action. The framework default ({@code DefaultAuthorizationIntrospector}) guarantees this by
 * evaluating each action through the exact same role→policy→action resolution the default
 * {@link Authorizer} uses. For a <strong>reconstructed</strong> context, the invariant does not apply
 * at all: the framework's exposed implementation ({@code NarrowingIntrospector}) rejects introspection
 * outright with {@link ReconstructedContextIntrospectionUnsupportedException} rather than compute an
 * answer that could silently disagree with a reconstructed context's live-resolved authority (Mode 2,
 * PRD identity-002 FR-ID-CA-010) — see that exception's javadoc for the full rationale.
 *
 * <p>The default implementation is <strong>in-memory and synchronous</strong>: it returns directly
 * rather than via a {@link io.vertx.core.Future}, because the decision is a pure function of the
 * actor's roles and the static policy catalogue.
 *
 * <p><strong>Narrower annotation (unreleased V1).</strong> {@link #capabilities(SecurityContext)}
 * amends this SPI to pair each allowed action with the {@link RequirementDescriptor}s contributed by
 * every installed {@link AuthorizationNarrower} that reports one for that action. This is an
 * <em>annotation</em>, not a filter: {@code capabilities} always covers exactly the actions
 * {@link #allowedActions(SecurityContext)} returns ({@code allowedActions(ctx)} must equal
 * {@code capabilities(ctx).stream().map(ActionCapability::action)}), so the Agreement invariant above
 * continues to describe the base, resource-independent permission surface. A non-empty
 * {@link ActionCapability#requirements()} means the capability exists but is conditionally gated by
 * one or more requirements (e.g. an active delegation grant, a minimum assurance level) — a caller
 * still evaluates the concrete, resource-specific {@link Authorizer#authorize(AuthorizationRequest)}
 * decision to learn whether a particular request satisfies those gates.
 */
public interface AuthorizationIntrospector {

    /**
     * Returns the set of registered actions the given actor is permitted to perform.
     *
     * <p>The result is a subset of the actions in the {@link ActionRegistry}: an action appears in
     * the result exactly when the authorization policy allows it for {@code ctx}. An actor with no
     * applicable grants (including one with no roles) yields an empty set.
     *
     * @param ctx the security context of the actor whose capabilities are being introspected; must
     *     not be {@code null}
     * @return an immutable {@link Set} of the {@link ActionRef}s the actor may perform; never
     *     {@code null}, possibly empty
     * @throws NullPointerException if {@code ctx} is {@code null}
     * @throws ReconstructedContextIntrospectionUnsupportedException if {@code ctx} is a reconstructed
     *     context (see the class-level "Agreement invariant" note); this is the framework's exposed
     *     implementation's behavior ({@code NarrowingIntrospector}) — the SPI itself does not mandate
     *     it of every implementation
     */
    Set<ActionRef> allowedActions(SecurityContext ctx);

    /**
     * Returns the set of registered actions the given actor is permitted to perform, each annotated
     * with the requirements (if any) that further gate it.
     *
     * <p>Membership is identical to {@link #allowedActions(SecurityContext)} — this method never
     * removes an action based on a narrower's requirement, it only attaches requirements. See the
     * class-level "Narrower annotation" note for the full contract.
     *
     * @param ctx the security context of the actor whose capabilities are being introspected; must
     *     not be {@code null}
     * @return an immutable {@link Set} of {@link ActionCapability} covering exactly the actions
     *     {@link #allowedActions(SecurityContext)} returns; never {@code null}, possibly empty
     * @throws NullPointerException if {@code ctx} is {@code null}
     * @throws ReconstructedContextIntrospectionUnsupportedException if {@code ctx} is a reconstructed
     *     context (see the class-level "Agreement invariant" note); this is the framework's exposed
     *     implementation's behavior ({@code NarrowingIntrospector}) — the SPI itself does not mandate
     *     it of every implementation
     */
    Set<ActionCapability> capabilities(SecurityContext ctx);
}
