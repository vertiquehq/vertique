// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
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
import io.vertx.core.Future;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AssuranceRequirementNarrower} — the minimum-assurance policy hook narrower
 * (PRD identity-002 FR-ID-CA-005, FR-ID-AR-003).
 *
 * <p>Pins: the no-requirement/existing-deny passthroughs, the below-minimum-level deny, the
 * reconstructed-context deny (even when the snapshot's own assurance would otherwise pass), the
 * freshness-decay deny driven purely by the injected {@link Clock}, the annotate-only
 * {@code requirementFor} introspection contract, and the FR-ID-AR-003 action-PATTERN authoring
 * contract — a wildcard action pattern (e.g. {@code "payments.refund.*"}) gates every action it
 * matches (the C1 spec-conformance fix: a previous exact-string-keyed lookup silently matched
 * nothing for a wildcard key), an exact pattern still gates its exact action, and a non-matching
 * action is unaffected even when other patterns are configured.
 */
class AssuranceRequirementNarrowerTest {

    private static final ActionRef ACTION = ActionRef.of("cms", "content", "delete");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "doc-1", Map.of());
    private static final PrincipalRef ACTOR = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());

    // --- no-requirement / existing-deny passthrough ---

    @Nested
    @DisplayName("nonGatedActionUnaffected")
    class NonGatedActionUnaffected {

        @Test
        @DisplayName("an action with no configured requirement passes the base decision through unchanged, and "
                + "requirementFor reports no requirement")
        void nonGatedActionUnaffected() {
            AssuranceRequirementNarrower narrower =
                    new AssuranceRequirementNarrower(AssuranceRequirementConfig.defaults(), fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.empty());
            AuthorizationDecision base = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

            AuthorizationDecision result = await(narrower.narrow(request(ctx), base));

            assertSame(base, result, "an ungated action must pass the base decision through unchanged");
            assertTrue(narrower.requirementFor(ctx, ACTION).isEmpty(), "an ungated action must report no requirement");
        }

        @Test
        @DisplayName("a gated action whose base decision is already a deny is returned unchanged")
        void existingDenyUnchanged() {
            AssuranceRequirementConfig config =
                    configWith(ACTION.value(), new AssuranceRequirement(1, Duration.ofMinutes(5)));
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.empty());
            AuthorizationDecision base = AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING);

            AuthorizationDecision result = await(narrower.narrow(request(ctx), base));

            assertSame(base, result, "an existing deny must be returned unchanged, never widened or re-annotated");
        }
    }

    // --- FR-ID-AR-003 action-PATTERN authoring contract ---

    @Nested
    @DisplayName("wildcardPatternGatesMatchingActions")
    class WildcardPatternGatesMatchingActions {

        @Test
        @DisplayName("a wildcard action-pattern key (\"payments.refund.*\") gates a matching action with "
                + "STEP_UP_REQUIRED when assurance is below the configured minimum — proves the wildcard "
                + "actually matches (the C1 regression: an exact-string-keyed lookup would silently ungate "
                + "this)")
        void wildcardPatternGatesMatchingActions() {
            ActionRef refundApprove = ActionRef.of("payments", "refund", "approve");
            AssuranceRequirement requirement = new AssuranceRequirement(2, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith("payments.refund.*", requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.of(assuranceOf(1, NOW)));

            AuthorizationDecision result = await(narrower.narrow(
                    request(ctx, refundApprove), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted(), "a wildcard pattern must gate an action it matches");
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, result.reasonCode());
            assertEquals(2, result.safeAttributes().get(AssuranceRequirementNarrower.MIN_LEVEL_ATTRIBUTE));
            assertEquals(
                    AssuranceRequirementNarrower.REASON_BELOW_MIN,
                    result.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }
    }

    @Nested
    @DisplayName("exactPatternStillGates")
    class ExactPatternStillGates {

        @Test
        @DisplayName("a fully-specified action pattern still gates its exact action with STEP_UP_REQUIRED")
        void exactPatternStillGates() {
            AssuranceRequirement requirement = new AssuranceRequirement(2, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.of(assuranceOf(1, NOW)));

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, result.reasonCode());
        }
    }

    @Nested
    @DisplayName("nonMatchingActionUngated")
    class NonMatchingActionUngated {

        @Test
        @DisplayName("an action matching no configured pattern passes the base decision through unchanged, and "
                + "requirementFor reports no requirement, even though other patterns are configured")
        void nonMatchingActionUngated() {
            AssuranceRequirementConfig config =
                    configWith(ACTION.value(), new AssuranceRequirement(1, Duration.ofMinutes(5)));
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            ActionRef unrelated = ActionRef.of("payments", "refund", "approve");
            SecurityContext ctx = liveCtxWithAssurance(Optional.empty());
            AuthorizationDecision base = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

            AuthorizationDecision result = await(narrower.narrow(request(ctx, unrelated), base));

            assertSame(base, result, "a non-matching action must pass the base decision through unchanged");
            assertTrue(
                    narrower.requirementFor(ctx, unrelated).isEmpty(),
                    "a non-matching action must report no requirement");
        }
    }

    // --- below-minimum assurance ---

    @Nested
    @DisplayName("belowMinAssuranceDeniesWithRequirement")
    class BelowMinAssuranceDeniesWithRequirement {

        @Test
        @DisplayName("a providerLevel below the configured minimum denies with STEP_UP_REQUIRED and carries the "
                + "unmet-requirement safeAttributes")
        void belowMinAssuranceDeniesWithRequirement() {
            AssuranceRequirement requirement = new AssuranceRequirement(2, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.of(assuranceOf(1, NOW)));

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, result.reasonCode());
            assertEquals(2, result.safeAttributes().get(AssuranceRequirementNarrower.MIN_LEVEL_ATTRIBUTE));
            assertEquals(300_000L, result.safeAttributes().get(AssuranceRequirementNarrower.MAX_AGE_MS_ATTRIBUTE));
            assertEquals(
                    AssuranceRequirementNarrower.REASON_BELOW_MIN,
                    result.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }

        @Test
        @DisplayName("a context with no AuthenticationAssurance at all denies with STEP_UP_REQUIRED, reason BELOW_MIN")
        void absentAssuranceDeniesBelowMin() {
            AssuranceRequirement requirement = new AssuranceRequirement(0, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.empty());

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, result.reasonCode());
            assertEquals(
                    AssuranceRequirementNarrower.REASON_BELOW_MIN,
                    result.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }
    }

    // --- reconstructed context ---

    @Nested
    @DisplayName("reconstructedContextDenied")
    class ReconstructedContextDenied {

        @Test
        @DisplayName("a reconstructed context denies with STEP_UP_REQUIRED, reason RECONSTRUCTED_NEEDS_STEP_UP, even "
                + "when its snapshot assurance would otherwise satisfy the requirement")
        void reconstructedContextDenied() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            // Snapshot assurance would otherwise satisfy the requirement: high level, fresh authTime.
            SecurityContext ctx = reconstructedCtxWithAssurance(assuranceOf(5, NOW));

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, result.reasonCode());
            assertEquals(
                    AssuranceRequirementNarrower.REASON_RECONSTRUCTED_NEEDS_STEP_UP,
                    result.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }
    }

    // --- freshness decay ---

    @Nested
    @DisplayName("assuranceDecaysWithInjectedClock")
    class AssuranceDecaysWithInjectedClock {

        @Test
        @DisplayName("a fresh authTime at clock T passes; advancing the injected clock past authTime+maxAge denies "
                + "the SAME context with STEP_UP_REQUIRED, reason DECAYED")
        void assuranceDecaysWithInjectedClock() {
            Instant authTime = Instant.parse("2026-01-01T00:00:00Z");
            Duration maxAge = Duration.ofMinutes(5);
            AssuranceRequirement requirement = new AssuranceRequirement(1, maxAge);
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            SecurityContext ctx = liveCtxWithAssurance(Optional.of(assuranceOf(1, authTime)));

            AssuranceRequirementNarrower freshNarrower =
                    new AssuranceRequirementNarrower(config, fixedAt(authTime.plus(Duration.ofMinutes(1))));
            AuthorizationDecision freshResult =
                    await(freshNarrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            assertTrue(freshResult.permitted(), "within the freshness window the permit must survive");

            AssuranceRequirementNarrower decayedNarrower =
                    new AssuranceRequirementNarrower(config, fixedAt(authTime.plus(Duration.ofMinutes(6))));
            AuthorizationDecision decayedResult = await(
                    decayedNarrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            assertFalse(decayedResult.permitted(), "past the freshness window the same context must be denied");
            assertEquals(AuthzReasonCodes.STEP_UP_REQUIRED, decayedResult.reasonCode());
            assertEquals(
                    AssuranceRequirementNarrower.REASON_DECAYED,
                    decayedResult.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }

        @Test
        @DisplayName("assurance present with a sufficient level but no authTime at all denies with STEP_UP_REQUIRED, "
                + "reason DECAYED")
        void absentAuthTimeDeniesDecayed() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            AuthenticationAssurance assurance =
                    new AuthenticationAssurance(Optional.empty(), Set.of(), Optional.empty(), Optional.of(5));
            SecurityContext ctx = liveCtxWithAssurance(Optional.of(assurance));

            AuthorizationDecision result =
                    await(narrower.narrow(request(ctx), AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));

            assertFalse(result.permitted());
            assertEquals(
                    AssuranceRequirementNarrower.REASON_DECAYED,
                    result.safeAttributes().get(AssuranceRequirementNarrower.REASON_ATTRIBUTE));
        }
    }

    // --- introspection ---

    @Nested
    @DisplayName("requirementForGatedActionReportsAssuranceRequirement")
    class RequirementForGatedActionReportsAssuranceRequirement {

        @Test
        @DisplayName("a gated action reports an ASSURANCE requirement describing the configured minimum, "
                + "regardless of the ctx passed in")
        void requirementForGatedActionReportsAssuranceRequirement() {
            AssuranceRequirement requirement = new AssuranceRequirement(2, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = configWith(ACTION.value(), requirement);
            AssuranceRequirementNarrower narrower = new AssuranceRequirementNarrower(config, fixedAt(NOW));
            SecurityContext ctx = liveCtxWithAssurance(Optional.empty());

            Optional<RequirementDescriptor> result = narrower.requirementFor(ctx, ACTION);

            assertTrue(result.isPresent());
            assertEquals("ASSURANCE", result.get().kind());
            assertEquals("minProviderLevel=2, maxAgeMs=300000", result.get().detail());
        }
    }

    // --- helpers ---

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "narrow() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx) {
        return request(ctx, ACTION);
    }

    private static AuthorizationRequest request(SecurityContext ctx, ActionRef action) {
        return new AuthorizationRequest(ctx, action.value(), RESOURCE, Map.of());
    }

    private static AssuranceRequirementConfig configWith(String actionPattern, AssuranceRequirement requirement) {
        return AssuranceRequirementConfig.fromJson(Map.of(actionPattern, requirement));
    }

    private static AuthenticationAssurance assuranceOf(int providerLevel, Instant authTime) {
        return new AuthenticationAssurance(
                Optional.empty(), Set.of(), Optional.of(authTime), Optional.of(providerLevel));
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static SecurityContext liveCtxWithAssurance(Optional<AuthenticationAssurance> assurance) {
        SecurityIdentity identity = SecurityIdentity.user(ACTOR);
        AuthenticationState auth =
                new AuthenticationState(DefaultAuthMethod.none(), List.of(), assurance, Optional.empty(), Map.of());
        return SecurityContexts.assemble(identity, auth, AuthorizationClaims.empty(), Optional.empty());
    }

    private static SecurityContext reconstructedCtxWithAssurance(AuthenticationAssurance assurance) {
        SecurityIdentity identity = SecurityIdentity.user(ACTOR);
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.of(assurance), Optional.empty(), Map.of());
        ReconstructionMarker marker = new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY);
        return SecurityContexts.assembleReconstructed(
                identity, auth, AuthorizationClaims.empty(), Optional.empty(), marker);
    }
}
