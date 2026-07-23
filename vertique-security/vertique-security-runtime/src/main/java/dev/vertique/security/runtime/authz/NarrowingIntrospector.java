// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionCapability;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationIntrospector;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.ReconstructedContextIntrospectionUnsupportedException;
import dev.vertique.security.authz.RequirementDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link AuthorizationIntrospector} decorator that annotates the base introspector's allowed
 * actions with the requirements an ordered set of {@link AuthorizationNarrower}s report for each
 * action, and is the framework's exposed {@link AuthorizationIntrospector} binding (see
 * {@code SecurityAuthzModule#authorizationIntrospector}).
 *
 * <p>{@link #allowedActions(SecurityContext)} delegates directly to the wrapped base introspector —
 * narrowing never changes <em>which</em> actions are reported, only annotates them. For each action
 * in the base allowed set, {@link #capabilities(SecurityContext)} folds
 * {@link AuthorizationNarrower#requirementFor(SecurityContext, ActionRef)} across the ordered
 * narrower set (see {@link AuthorizationNarrowerOrdering}) and collects <strong>every</strong>
 * non-empty requirement it finds — not merely the first — since two independent narrowers may each
 * gate the same action for unrelated reasons. This keeps the pinned equivalence
 * {@code allowedActions(ctx) == capabilities(ctx).stream().map(ActionCapability::action)} true by
 * construction.
 *
 * <p>A non-empty requirement set is informational: it tells a caller this capability exists but is
 * conditionally gated (e.g. by an active delegation grant, a minimum assurance level). The concrete,
 * resource-specific enforcement of those gates happens at authorize-time via the corresponding
 * {@code NarrowingAuthorizer}, which this class does not itself invoke — introspection has no
 * concrete {@link dev.vertique.security.authz.ResourceRef} to evaluate against.
 *
 * <p>With an empty narrower set, this decorator is behavior-identical to the base
 * {@link AuthorizationIntrospector} it wraps: every returned {@link ActionCapability} carries an
 * un-annotated (empty) requirement set.
 *
 * <p><strong>Reconstructed contexts are rejected outright.</strong> Both {@link #allowedActions} and
 * {@link #capabilities} throw {@link ReconstructedContextIntrospectionUnsupportedException} when
 * {@code ctx.}{@link SecurityContext#reconstruction() reconstruction()} is present, <em>before</em>
 * consulting the base introspector or any narrower. A reconstructed context's current authority is
 * resolved live at authorize-time by Mode 2 (PRD identity-002 FR-ID-CA-010) — computing an
 * introspection answer from the context's currently held claims could silently disagree with what
 * {@code authorize()} would actually decide for the same actor, which would be an invisible Agreement
 * invariant violation. This decorator is the natural guard point: it is the framework's exposed
 * {@link AuthorizationIntrospector} binding, so the guard applies uniformly regardless of which base
 * introspector or narrower set is installed.
 */
public final class NarrowingIntrospector implements AuthorizationIntrospector {

    private final AuthorizationIntrospector base;
    private final List<AuthorizationNarrower> orderedNarrowers;

    /**
     * Creates the decorator from the base introspector and the contributed narrower set.
     *
     * @param base      the wrapped base {@link AuthorizationIntrospector}; must not be {@code null}
     * @param narrowers the contributed narrowers; sorted internally by
     *                  {@link dev.vertique.core.extension.OrderedExtension#comparator()}; must not
     *                  be {@code null}
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if two narrowers share the same {@code (priority, orderKey)} pair
     */
    public NarrowingIntrospector(AuthorizationIntrospector base, Set<AuthorizationNarrower> narrowers) {
        this.base = Objects.requireNonNull(base, "base");
        this.orderedNarrowers = AuthorizationNarrowerOrdering.sortedAndValidated(narrowers);
    }

    @Override
    public Set<ActionRef> allowedActions(SecurityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        rejectReconstructed(ctx);
        return base.allowedActions(ctx);
    }

    @Override
    public Set<ActionCapability> capabilities(SecurityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        rejectReconstructed(ctx);
        return base.allowedActions(ctx).stream()
                .map(action -> new ActionCapability(action, requirementsFor(ctx, action)))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Rejects introspection of a reconstructed {@link SecurityContext}. See the class javadoc's
     * "Reconstructed contexts are rejected outright" section for the full rationale.
     *
     * @param ctx the context being introspected
     * @throws ReconstructedContextIntrospectionUnsupportedException if {@code ctx} is a reconstructed
     *     context
     */
    private static void rejectReconstructed(SecurityContext ctx) {
        if (ctx.reconstruction().isPresent()) {
            throw new ReconstructedContextIntrospectionUnsupportedException(
                    "Capability introspection (allowedActions()/capabilities()) is unsupported for a "
                            + "reconstructed SecurityContext: current authority is resolved live at "
                            + "authorize-time (Mode 2, PRD identity-002 FR-ID-CA-010); call "
                            + "Authorizer.authorize(...) for the specific action instead.");
        }
    }

    /**
     * Folds {@link AuthorizationNarrower#requirementFor(SecurityContext, ActionRef)} across the
     * ordered narrower set, collecting every non-empty result.
     *
     * @param ctx    the security context of the actor being introspected
     * @param action the action being described
     * @return the requirements every narrower in the ordered set reports for this action; empty if
     *     none report one
     */
    private Set<RequirementDescriptor> requirementsFor(SecurityContext ctx, ActionRef action) {
        return orderedNarrowers.stream()
                .map(narrower -> narrower.requirementFor(ctx, action))
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }
}
