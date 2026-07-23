// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * Stable vocabulary of machine-readable reason codes carried on an {@link AuthorizationDecision}.
 *
 * <p>Each constant is a frozen wire value: it is emitted on authorization events and may be returned
 * in error responses, so the strings must not drift. The set is a deliberate <strong>superset</strong>
 * of the codes the {@code vertique-core} default engine ({@code DefaultAuthorizer}) produces — it also
 * reserves codes for concerns the engine itself does not decide (e.g. {@link #AUTHENTICATION_REQUIRED}
 * and the scope-related codes are owned by the enforcement layer / future evaluators). Exactly one
 * top-level reason code is reported per decision.
 *
 * <p>This class is not instantiable.
 */
public final class AuthzReasonCodes {

    private AuthzReasonCodes() {
        throw new AssertionError("AuthzReasonCodes is not instantiable");
    }

    // --- permit ---

    /** The requested action is permitted. */
    public static final String PERMITTED = "PERMITTED";

    // --- authentication / identity ---

    /**
     * Authentication is required but the request is unauthenticated. Reserved for the enforcement
     * layer; the default action engine does not produce this code.
     */
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    /** The principal carries no {@link AuthorityKind#ROLE} claim, so no policy can apply. */
    public static final String ROLE_MISSING = "ROLE_MISSING";

    /**
     * The route or operation is unconditionally denied (e.g. a Jakarta {@code @DenyAll} policy).
     * Reserved for the enforcement layer; the default action engine does not produce this code.
     */
    public static final String DENY_ALL = "DENY_ALL";

    /**
     * The requested action requires stronger or fresher authentication than the current
     * {@link dev.vertique.security.SecurityContext} holds — e.g. a minimum IdP-reported
     * {@link dev.vertique.security.AuthenticationAssurance#providerLevel() provider level}, a fresh
     * {@link dev.vertique.security.AuthenticationAssurance#authTime() authTime} within a configured
     * freshness window, or a live re-verification the caller must complete before retrying (PRD
     * identity-002 FR-ID-CA-005). Reserved for the enforcement layer's minimum-assurance narrower;
     * the default action engine does not produce this code.
     */
    public static final String STEP_UP_REQUIRED = "STEP_UP_REQUIRED";

    // --- scope (reserved for the enforcement layer / future evaluators) ---

    /** A required scope is entirely absent. Reserved; not produced by the default action engine. */
    public static final String SCOPE_MISSING = "SCOPE_MISSING";

    /** A scope is present but insufficient. Reserved; not produced by the default action engine. */
    public static final String SCOPE_INSUFFICIENT = "SCOPE_INSUFFICIENT";

    // --- action / policy resolution ---

    /** The requested action is not present in the {@link ActionRegistry}. */
    public static final String ACTION_NOT_REGISTERED = "ACTION_NOT_REGISTERED";

    /** A mapped policy was found but no {@link Effect#ALLOW} statement matches the requested action. */
    public static final String ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED";

    /** A policy name mapped from the actor's roles is absent from every {@link PolicyDefinitionSource}. */
    public static final String POLICY_NOT_FOUND = "POLICY_NOT_FOUND";

    /** The actor's roles map to no policy name at all (no {@link RolePolicyResolver} produced one). */
    public static final String ROLE_POLICY_MISSING = "ROLE_POLICY_MISSING";

    /** A resolved policy is structurally invalid. Reserved; not produced by the default action engine. */
    public static final String POLICY_INVALID = "POLICY_INVALID";

    // --- fail-closed / instance eligibility ---

    /**
     * An unexpected internal error occurred while evaluating the request; the engine fails closed and
     * denies rather than propagating the exception.
     */
    public static final String INTERNAL_AUTHZ_ERROR = "INTERNAL_AUTHZ_ERROR";

    /**
     * Instance-level eligibility evaluation failed. Reserved for future instance-aware evaluators; not
     * produced by the default action engine.
     */
    public static final String INSTANCE_ELIGIBILITY_FAILED = "INSTANCE_ELIGIBILITY_FAILED";

    // --- reconstructed-authority resolution (Mode 2) ---

    /**
     * A {@link PrincipalAuthorityResolver} failed, timed out, or returned an ambiguous result while
     * re-resolving a reconstructed principal's current authority; the evaluation fails closed to a
     * deny. Reserved for the Mode-2 authorizer; not produced by the default action engine.
     */
    public static final String AUTHORITY_RESOLUTION_FAILED = "AUTHORITY_RESOLUTION_FAILED";
}
