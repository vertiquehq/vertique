// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.authz.ReconstructedAuthorityMode;
import java.util.Objects;

/**
 * The typed, <strong>framework-mediated</strong> verified-reconstruction signal a
 * {@link SecurityContext} carries via {@link SecurityContext#reconstruction()} (PRD identity-002
 * §14.3 Phase-2 Appendix).
 *
 * <p>A Mode-2 authorizer MUST key off <em>this</em> typed accessor to decide whether a context is
 * a framework-verified reconstruction — <strong>never</strong> the descriptive
 * {@code identity.reconstructed=true} string attribute on
 * {@link AuthenticationState#safeAttributes()}, which any code populating {@code safeAttributes}
 * could set on an otherwise live-authored context. This record can only be produced by
 * {@link SecurityContexts#assembleReconstructed}, whose sanctioned callers are the framework's
 * verified-reconstruction path ({@code DefaultIdentityReconstruction}) and the Mode-2 authorizer's
 * own evaluation-context rebuild.
 *
 * <p><strong>Not a cryptographic unforgeable guarantee.</strong> This marker is trusted under the
 * same in-process trust model as {@link SecurityContexts#assemble} — any in-process code can
 * already present arbitrary claims through that general-purpose factory, and
 * {@link SecurityContexts#assembleReconstructed} is no stronger: it is a typed signal that
 * distinguishes "the framework assembled this as a reconstruction" from "the framework assembled
 * this as a live context", not a proof that the underlying reconstruction was itself legitimate.
 * Since this record carries no principal of its own (below), the most a caller hand-building a
 * bogus marker could do is set an untrustworthy {@link SecurityIdentity#actor() actor} on an
 * otherwise ordinary {@link SecurityContexts#assemble} call — a capability {@link
 * SecurityContexts#assemble} already grants to any in-process caller.
 *
 * <p>Carries <strong>no principal of its own</strong>. Per FR-ID-DG-006, v1 delegation resolves the
 * intersection of the context's own {@link SecurityIdentity#actor() actor} authority and the grant
 * scope — subject-authority evaluation (impersonation) is explicitly out of v1 scope. A Mode-2
 * authorizer (e.g. {@code ReconstructedAuthorityResolvingAuthorizer}) therefore derives the
 * {@link dev.vertique.security.authz.PrincipalKey} to re-resolve directly from
 * {@code SecurityContext#identity()#actor()} — never from this marker — so both
 * {@code resumeAsPrincipal} (actor == the resumed principal) and {@code deferredExecution}
 * (actor == the executing service) resolve the <strong>acting</strong> principal's own current
 * authority. The subject-of-record, when present, stays on {@link SecurityIdentity#subject()} as
 * attribution only and is never re-resolved for authority.
 *
 * @param mode the reconstruction's authority <strong>disposition</strong> —
 *             {@link ReconstructedAuthorityMode#ATTRIBUTION_ONLY} for a default reconstruction
 *             (the captured claims are audit-lineage only; the authority is re-resolved live
 *             iff Mode 2 is installed) or {@link ReconstructedAuthorityMode#CAPTURED} for a
 *             Mode-3 reconstruction (the captured claims ARE current authority).
 *             {@link ReconstructedAuthorityMode#LIVE_RESOLVED} is an <em>evaluation outcome</em>,
 *             never a marker value — it must not be used here and is rejected by the compact
 *             constructor with {@link IllegalArgumentException}; must not be {@code null}
 */
public record ReconstructionMarker(ReconstructedAuthorityMode mode) {

    /**
     * Compact constructor — validates that {@code mode} is non-null and rejects
     * {@link ReconstructedAuthorityMode#LIVE_RESOLVED}, which is an evaluation outcome a Mode-2
     * authorizer stamps onto a produced {@code AuthorizationDecision}, never a reconstruction
     * disposition a marker may carry.
     *
     * @throws NullPointerException     if {@code mode} is {@code null}
     * @throws IllegalArgumentException if {@code mode} is
     *                                  {@link ReconstructedAuthorityMode#LIVE_RESOLVED}
     */
    public ReconstructionMarker {
        Objects.requireNonNull(mode, "mode");
        if (mode == ReconstructedAuthorityMode.LIVE_RESOLVED) {
            throw new IllegalArgumentException(
                    "mode must not be LIVE_RESOLVED — it is an evaluation outcome, never a reconstruction "
                            + "disposition a marker may carry");
        }
    }
}
