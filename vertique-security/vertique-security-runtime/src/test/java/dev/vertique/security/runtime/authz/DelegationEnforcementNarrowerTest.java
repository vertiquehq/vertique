// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationGrantDecision;
import dev.vertique.security.DelegationGrantValidator;
import dev.vertique.security.DelegationReasonCodes;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.RequirementDescriptor;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DelegationEnforcementNarrower} — the first concrete
 * {@link dev.vertique.security.authz.AuthorizationNarrower} (PRD identity-002 FR-ID-DG-006).
 *
 * <p>Pins: the non-delegated passthrough, the four-quadrant intersection semantics
 * (base permit/deny &times; grant permit/deny), the missing/expired-grant fail-closed deny, the
 * audit-visibility attributes carried on every delegated decision, and the {@code requirementFor}
 * introspection annotation.
 */
class DelegationEnforcementNarrowerTest {

    private static final ActionRef ACTION = ActionRef.of("cms", "content", "update");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "doc-1", Map.of());
    private static final PrincipalRef ACTOR = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
    private static final PrincipalRef SUBJECT = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
    private static final String GRANT_ID = "grant-42";
    private static final DelegationContext DELEGATION =
            new DelegationContext("on-behalf-of", GRANT_ID, Optional.empty(), Map.of());

    // --- non-delegated passthrough ---

    @Nested
    @DisplayName("nonDelegatedContextPassthrough")
    class NonDelegatedContextPassthrough {

        @Test
        @DisplayName("a context with no subject/delegation passes the base decision through unchanged, and "
                + "requirementFor reports no requirement")
        void nonDelegatedContextPassthrough() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(neverCalledValidator());
            SecurityContext ctx = ctxFor(SecurityIdentity.service(ACTOR));
            AuthorizationDecision base = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

            AuthorizationDecision result = await(narrower.narrow(request(ctx), base));

            assertSame(base, result, "a non-delegated context must pass the base decision through unchanged");
            assertTrue(
                    narrower.requirementFor(ctx, ACTION).isEmpty(),
                    "a non-delegated actor must report no delegation requirement");
        }
    }

    // --- four-quadrant intersection ---

    @Nested
    @DisplayName("fourQuadrantAuthority")
    class FourQuadrantAuthority {

        @Test
        @DisplayName("base PERMIT + grant PERMIT -> PERMIT")
        void permitPermitPermits() {
            AuthorizationDecision result = evaluate(
                    AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED),
                    grantDecision(true, DelegationReasonCodes.GRANT_VALID));

            assertTrue(result.permitted(), "intersection satisfied: both base and grant permit");
        }

        @Test
        @DisplayName("base PERMIT + grant DENY -> narrowed to DENY, surfacing the grant's reason")
        void permitDenyDenies() {
            AuthorizationDecision result = evaluate(
                    AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED),
                    grantDecision(false, DelegationReasonCodes.GRANT_OUT_OF_SCOPE));

            assertFalse(result.permitted(), "the grant does not cover this request");
            assertEquals(DelegationReasonCodes.GRANT_OUT_OF_SCOPE, result.reasonCode());
        }

        @Test
        @DisplayName("base DENY + grant PERMIT -> base DENY survives (no-widen: the narrower adds no authority)")
        void denyPermitStaysDenied() {
            AuthorizationDecision result = evaluate(
                    AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING),
                    grantDecision(true, DelegationReasonCodes.GRANT_VALID));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.ROLE_MISSING, result.reasonCode(), "the base deny's own reason survives");
        }

        @Test
        @DisplayName("base DENY + grant DENY -> DENY")
        void denyDenyStaysDenied() {
            AuthorizationDecision result = evaluate(
                    AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING),
                    grantDecision(false, DelegationReasonCodes.GRANT_EXPIRED));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.ROLE_MISSING, result.reasonCode(), "the base deny's own reason survives");
        }

        private AuthorizationDecision evaluate(AuthorizationDecision base, DelegationGrantDecision grantDecision) {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(fixed(grantDecision));
            SecurityContext ctx = delegatedCtx();
            return await(narrower.narrow(request(ctx), base));
        }
    }

    // --- missing/expired grant fail-closed ---

    @Nested
    @DisplayName("missingOrExpiredGrantDenies")
    class MissingOrExpiredGrantDenies {

        @Test
        @DisplayName("a missing grant over a base PERMIT denies with reason GRANT_NOT_FOUND")
        void missingGrantDenies() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(
                    fixed(grantDecision(false, DelegationReasonCodes.GRANT_NOT_FOUND)));
            SecurityContext ctx = delegatedCtx();

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(DelegationReasonCodes.GRANT_NOT_FOUND, result.reasonCode());
        }

        @Test
        @DisplayName("an expired grant over a base PERMIT denies with reason GRANT_EXPIRED")
        void expiredGrantDenies() {
            DelegationEnforcementNarrower narrower =
                    new DelegationEnforcementNarrower(fixed(grantDecision(false, DelegationReasonCodes.GRANT_EXPIRED)));
            SecurityContext ctx = delegatedCtx();

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(DelegationReasonCodes.GRANT_EXPIRED, result.reasonCode());
        }
    }

    // --- framework-scheduled deferred work is not grant-backed delegation ---

    @Nested
    @DisplayName("frameworkScheduledDeferredNotDeniedAsGrant")
    class FrameworkScheduledDeferredNotDeniedAsGrant {

        @Test
        @DisplayName("a reconstructed framework-scheduled deferred context (kind=DEFERRED_EXECUTION_KIND, "
                + "synthetic authorityId) over a base PERMIT passes through unchanged — never denied-all, and "
                + "the grant validator is never consulted")
        void frameworkScheduledDeferredNotDeniedAsGrant() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(neverCalledValidator());
            SecurityContext ctx = deferredCtx();
            AuthorizationDecision base = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

            AuthorizationDecision result = await(narrower.narrow(request(ctx), base));

            assertSame(
                    base,
                    result,
                    "framework-scheduled deferred work must pass through unchanged — its authority is the "
                            + "actor's own (resolved live by Mode 2), not a grant to validate");
        }

        @Test
        @DisplayName("a forged/misconfigured deferred-execution kind on a NON-reconstructed (live) context is "
                + "not passed through — it is validated as an ordinary grant-backed delegation and denies, "
                + "never a silent keep-permit (C2 residual fail-open fix)")
        void forgedDeferredKindOnLiveContextNotPassedThrough() {
            // Validator returns a deny (GRANT_NOT_FOUND) for the requested authorityId — proving the
            // narrower actually validated this context as a grant rather than passing it through. If
            // the C2 residual bug were still present, narrow() would return `base` (permitted)
            // unchanged without ever consulting this validator.
            DelegationEnforcementNarrower narrower =
                    new DelegationEnforcementNarrower(fixed(new DelegationGrantDecision(
                            false, DelegationReasonCodes.GRANT_NOT_FOUND, "deferred-execution", Optional.empty())));
            SecurityContext ctx = forgedDeferredKindLiveCtx();
            AuthorizationDecision base = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

            AuthorizationDecision result = await(narrower.narrow(request(ctx), base));

            assertFalse(
                    result.permitted(),
                    "a forged/misconfigured deferred-execution kind on a live (non-reconstructed) context "
                            + "must be validated as grant-backed delegation, not silently passed through with "
                            + "the base decision's authority");
            assertTrue(
                    narrower.requirementFor(ctx, ACTION).isPresent(),
                    "requirementFor must agree with narrow(): a live context carrying this kind reports the "
                            + "ordinary delegation requirement, not the deferred-work no-requirement passthrough");
        }

        @Test
        @DisplayName("a genuinely grant-backed delegation (a real grant id, non-deferred kind) still enforces "
                + "actor intersect grant as before")
        void grantBackedDelegationStillEnforced() {
            AuthorizationDecision result = evaluate(
                    AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED),
                    grantDecision(false, DelegationReasonCodes.GRANT_OUT_OF_SCOPE));

            assertFalse(
                    result.permitted(),
                    "a genuinely grant-backed delegation must still be validated and can still be narrowed to deny");
        }

        @Test
        @DisplayName("a framework-scheduled deferred context reports no delegation requirement — matching "
                + "narrow()'s pass-through (the narrow-deny <-> requirement-present Agreement invariant)")
        void frameworkScheduledDeferredHasNoDelegationRequirement() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(neverCalledValidator());
            SecurityContext ctx = deferredCtx();

            assertTrue(
                    narrower.requirementFor(ctx, ACTION).isEmpty(),
                    "framework-scheduled deferred work must report no delegation requirement, matching "
                            + "narrow()'s pass-through — a synthetic scheduling id is not a grant requirement");
        }

        private AuthorizationDecision evaluate(AuthorizationDecision base, DelegationGrantDecision grantDecision) {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(fixed(grantDecision));
            SecurityContext ctx = delegatedCtx();
            return await(narrower.narrow(request(ctx), base));
        }
    }

    // --- validator failure fails closed with GRANT_LOOKUP_FAILED (never an exceptional Future) ---

    @Nested
    @DisplayName("validatorFailureDeniesWithLookupFailed")
    class ValidatorFailureDeniesWithLookupFailed {

        @Test
        @DisplayName("a validator returning a failed Future over a base PERMIT denies with GRANT_LOOKUP_FAILED, "
                + "as an already-succeeded future carrying the SPI's required reason and grant correlation")
        void failedFutureDeniesWithLookupFailed() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(failingValidator());
            SecurityContext ctx = delegatedCtx();

            Future<AuthorizationDecision> future =
                    narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

            assertTrue(future.succeeded(), "a validator lookup failure must never propagate as a failed Future");
            AuthorizationDecision result = future.result();
            assertFalse(result.permitted());
            assertEquals(DelegationReasonCodes.GRANT_LOOKUP_FAILED, result.reasonCode());
            assertEquals("USER:user-1", result.safeAttributes().get(DelegationEnforcementNarrower.SUBJECT_ATTRIBUTE));
            assertEquals(GRANT_ID, result.safeAttributes().get(DelegationEnforcementNarrower.AUTHORITY_ID_ATTRIBUTE));
        }

        @Test
        @DisplayName("a validator throwing synchronously over a base PERMIT also denies with GRANT_LOOKUP_FAILED, "
                + "as an already-succeeded future")
        void synchronousThrowDeniesWithLookupFailed() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(throwingValidator());
            SecurityContext ctx = delegatedCtx();

            Future<AuthorizationDecision> future =
                    narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

            assertTrue(
                    future.succeeded(),
                    "a contract-violating synchronous throw must never propagate as a failed/exceptional Future");
            AuthorizationDecision result = future.result();
            assertFalse(result.permitted());
            assertEquals(DelegationReasonCodes.GRANT_LOOKUP_FAILED, result.reasonCode());
        }

        private DelegationGrantValidator failingValidator() {
            return (actor, subject, scopeKind, scopeRef, grantId) -> Future.failedFuture("grant store unavailable");
        }

        private DelegationGrantValidator throwingValidator() {
            return (actor, subject, scopeKind, scopeRef, grantId) -> {
                throw new RuntimeException("contract-violating synchronous throw");
            };
        }
    }

    // --- grant-id correlation: a miscorrelating validator must not silently stamp a mismatched grant ---

    @Nested
    @DisplayName("grantIdMismatchDeniesClosed")
    class GrantIdMismatchDeniesClosed {

        @Test
        @DisplayName("a validator returning permitted=true but a DIFFERENT grantId than requested denies closed")
        void grantIdMismatchDeniesClosed() {
            DelegationGrantDecision mismatched =
                    new DelegationGrantDecision(true, DelegationReasonCodes.GRANT_VALID, "G-good", Optional.empty());
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(fixed(mismatched));
            SecurityContext ctx = delegatedCtx(); // requests GRANT_ID ("grant-42"), not "G-good"

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(
                    result.permitted(),
                    "a miscorrelating validator permitting a different grant must not silently stamp the "
                            + "requested authorityId onto an unrelated permit");
        }
    }

    // --- audit visibility ---

    @Nested
    @DisplayName("decisionEventExposesSubjectAndAuthorityId")
    class DecisionEventExposesSubjectAndAuthorityId {

        @Test
        @DisplayName("a delegation-narrowed permit decision exposes the subject and authorityId in safeAttributes")
        void exposesSubjectAndAuthorityIdOnPermit() {
            DelegationEnforcementNarrower narrower =
                    new DelegationEnforcementNarrower(fixed(grantDecision(true, DelegationReasonCodes.GRANT_VALID)));
            SecurityContext ctx = delegatedCtx();

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertEquals("USER:user-1", result.safeAttributes().get(DelegationEnforcementNarrower.SUBJECT_ATTRIBUTE));
            assertEquals(GRANT_ID, result.safeAttributes().get(DelegationEnforcementNarrower.AUTHORITY_ID_ATTRIBUTE));
        }

        @Test
        @DisplayName("a delegation-narrowed deny decision also exposes the subject and authorityId in safeAttributes")
        void exposesSubjectAndAuthorityIdOnDeny() {
            DelegationEnforcementNarrower narrower =
                    new DelegationEnforcementNarrower(fixed(grantDecision(false, DelegationReasonCodes.GRANT_EXPIRED)));
            SecurityContext ctx = delegatedCtx();

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertEquals("USER:user-1", result.safeAttributes().get(DelegationEnforcementNarrower.SUBJECT_ATTRIBUTE));
            assertEquals(GRANT_ID, result.safeAttributes().get(DelegationEnforcementNarrower.AUTHORITY_ID_ATTRIBUTE));
        }
    }

    // --- introspection ---

    @Nested
    @DisplayName("requirementForDelegatedActionReportsDelegation")
    class RequirementForDelegatedActionReportsDelegation {

        @Test
        @DisplayName("a delegated context reports a delegation requirement naming the grant id, for any action")
        void requirementForDelegatedActionReportsDelegation() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(neverCalledValidator());
            SecurityContext ctx = delegatedCtx();

            Optional<RequirementDescriptor> requirement = narrower.requirementFor(ctx, ACTION);

            assertTrue(requirement.isPresent());
            assertEquals(new RequirementDescriptor("delegation", GRANT_ID), requirement.get());
        }
    }

    // --- edge case: unparseable action fails closed ---

    @Nested
    @DisplayName("malformedActionFailsClosed")
    class MalformedActionFailsClosed {

        @Test
        @DisplayName("an action string that doesn't parse as an ActionRef narrows a base PERMIT to a fail-closed DENY")
        void malformedActionFailsClosed() {
            DelegationEnforcementNarrower narrower = new DelegationEnforcementNarrower(neverCalledValidator());
            SecurityContext ctx = delegatedCtx();
            AuthorizationRequest malformedRequest = new AuthorizationRequest(ctx, "not-canonical", RESOURCE, Map.of());

            AuthorizationDecision result =
                    await(narrower.narrow(malformedRequest, AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR, result.reasonCode());
        }
    }

    // --- helpers ---

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "narrow() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx) {
        return new AuthorizationRequest(ctx, ACTION.value(), RESOURCE, Map.of());
    }

    private static SecurityContext delegatedCtx() {
        SecurityIdentity identity =
                new SecurityIdentity(ACTOR, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.empty());
        return ctxFor(identity);
    }

    /**
     * A <strong>real, framework-verified reconstruction</strong> carrying a framework-scheduled
     * deferred-execution delegation — the only shape {@link DelegationEnforcementNarrower#narrow}
     * passes through unchanged. Built via {@link SecurityContexts#assembleReconstructed}, so
     * {@link SecurityContext#reconstruction()} is present (C2 residual fix: the pass-through requires
     * this typed signal, never {@code kind} alone).
     */
    private static SecurityContext deferredCtx() {
        DelegationContext deferredDelegation = new DelegationContext(
                DelegationContext.DEFERRED_EXECUTION_KIND, "deferred-execution", Optional.empty(), Map.of());
        SecurityIdentity identity =
                new SecurityIdentity(ACTOR, Optional.of(SUBJECT), Optional.of(deferredDelegation), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        ReconstructionMarker marker = new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY);
        return SecurityContexts.assembleReconstructed(
                identity, auth, AuthorizationClaims.empty(), Optional.empty(), marker);
    }

    /**
     * A <strong>live (non-reconstructed)</strong> context carrying a {@link DelegationContext} whose
     * {@code kind} is {@link DelegationContext#DEFERRED_EXECUTION_KIND} — either misconfigured or
     * forged. {@link SecurityContext#reconstruction()} is empty (built via
     * {@link SecurityContexts#assemble}), so this must NOT be passed through by
     * {@link DelegationEnforcementNarrower#narrow} despite carrying the deferred-execution kind (the
     * C2 residual fail-open this test guards against).
     */
    private static SecurityContext forgedDeferredKindLiveCtx() {
        DelegationContext forgedDeferredDelegation = new DelegationContext(
                DelegationContext.DEFERRED_EXECUTION_KIND, "deferred-execution", Optional.empty(), Map.of());
        SecurityIdentity identity = new SecurityIdentity(
                ACTOR, Optional.of(SUBJECT), Optional.of(forgedDeferredDelegation), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assemble(identity, auth, AuthorizationClaims.empty(), Optional.empty());
    }

    private static SecurityContext ctxFor(SecurityIdentity identity) {
        return new StubSecurityContext(identity);
    }

    private static DelegationGrantDecision grantDecision(boolean permitted, String reasonCode) {
        return new DelegationGrantDecision(permitted, reasonCode, GRANT_ID, Optional.empty());
    }

    private static DelegationGrantValidator fixed(DelegationGrantDecision decision) {
        return (actor, subject, scopeKind, scopeRef, grantId) -> Future.succeededFuture(decision);
    }

    /** A validator that fails the test if invoked — used for scenarios that must not reach the grant lookup. */
    private static DelegationGrantValidator neverCalledValidator() {
        return (actor, subject, scopeKind, scopeRef, grantId) -> {
            throw new AssertionError("DelegationGrantValidator.validate() must not be called in this scenario");
        };
    }

    /** Minimal {@link SecurityContext} stub exposing a controllable {@link SecurityIdentity}. */
    private record StubSecurityContext(SecurityIdentity identity) implements SecurityContext {
        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }
}
