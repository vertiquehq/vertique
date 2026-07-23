// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Selects how a reconstructed {@code SecurityContext}'s current authority is determined (PRD
 * identity-002 §14.3 Phase-2 Appendix).
 *
 * <p>Each constant names one of the three reconstruction-authority strategies the framework
 * supports; the reconstructed context's typed
 * {@code dev.vertique.security.SecurityContext#reconstruction()} marker is present under every
 * mode — this enum only selects how {@code SecurityContext#authorization()} is populated.
 */
public enum ReconstructedAuthorityMode {

    /**
     * Phase-1 default: the snapshot's captured {@link AuthorityClaim}s are audit lineage only,
     * never presented as current authority — a reconstructed context's
     * {@code SecurityContext#authorization()} is always {@link AuthorizationClaims#empty()}.
     */
    ATTRIBUTION_ONLY,

    /**
     * Mode 2: current authority is re-resolved live from the reconstructed principal's durable
     * {@link PrincipalKey} via {@link PrincipalAuthorityResolver}, never trusting the snapshot's
     * captured claims.
     */
    LIVE_RESOLVED,

    /**
     * Mode 3: the snapshot's captured claims are trusted and carried forward as-is as the
     * reconstructed context's current authority.
     */
    CAPTURED;

    /**
     * The {@code AuthorizationDecision#safeAttributes()} key under which an evaluated authority
     * mode is surfaced, so an embedding {@code AuthorizationDecisionEvent} can distinguish which
     * strategy produced a given decision. Absence of this key on a decision means the request was
     * not stamped by a Mode-2/Mode-3 authority-mode decorator (either a non-reconstructed context,
     * or a reconstructed context under the Phase-1 {@link #ATTRIBUTION_ONLY} default with no
     * decorator installed).
     */
    public static final String DECISION_ATTRIBUTE = "authz.authority.mode";
}
