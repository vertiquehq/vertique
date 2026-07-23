// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.RequirementDescriptor;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReconstructedAuthorityResolvingAuthorizer} — the Mode-2/Mode-3 outermost
 * {@link Authorizer} decorator that re-resolves a reconstructed context's current authority live,
 * at authorization time, unless the context's own marker carries the {@code CAPTURED} disposition
 * (PRD identity-002 §14.3 Phase-2 Appendix).
 *
 * <p>Pins: live re-resolution on an {@code ATTRIBUTION_ONLY}-marked reconstruction, fail-closed
 * resolver-failure deny, the {@link ReconstructedAuthorityResolvingAuthorizer#AUTHORITY_MODE_ATTRIBUTE}
 * stamp, that narrowing still applies over the re-resolved claims, the non-reconstructed passthrough
 * (resolver never consulted), the typed-marker-not-string-attribute trigger, that the frozen
 * snapshot's own (possibly stale) claims never authorize an {@code ATTRIBUTION_ONLY} reconstruction
 * — only the resolver's live answer governs — and that a {@code CAPTURED}-marked reconstruction is
 * never live-resolved (the resolver is never consulted; the context's own claims govern as-is).
 */
class ReconstructedAuthorityResolvingAuthorizerTest {

    private static final ActionRef ACTION = ActionRef.of("cms", "content", "read");
    private static final ResourceRef RESOURCE = new ResourceRef("content", "doc-1", Map.of());
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(PrincipalType.USER, "user-1");
    private static final String REQUIRED_ROLE = "admin";

    // --- Mode-2 live resolution on permit ---

    @Nested
    @DisplayName("reconstructedContextResolvesLiveClaims")
    class ReconstructedContextResolvesLiveClaims {

        @Test
        @DisplayName("a reconstructed context whose resolver returns the permitting ROLE is permitted, stamped "
                + "with authz.authority.mode=LIVE_RESOLVED")
        void reconstructedContextResolvesLiveClaims() {
            PrincipalAuthorityResolver resolver = fixedResolver(PRINCIPAL, claimsWithRole(REQUIRED_ROLE));
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(rolePermittingAuthorizer(), resolver);
            SecurityContext ctx = reconstructedCtx(AuthorizationClaims.empty());

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertTrue(decision.permitted(), "the resolver's live ROLE claim must permit");
            assertEquals(
                    ReconstructedAuthorityMode.LIVE_RESOLVED.name(),
                    decision.safeAttributes().get(ReconstructedAuthorityResolvingAuthorizer.AUTHORITY_MODE_ATTRIBUTE));
        }
    }

    // --- Mode-2 resolves the actor, never the subject-of-record ---

    @Nested
    @DisplayName("resolvesActorNotSubject")
    class ResolvesActorNotSubject {

        @Test
        @DisplayName("a deferred-style reconstructed context (actor=SERVICE, subject=USER) resolves the "
                + "actor/service key — never the subject's — per FR-ID-DG-006 (subject-authority evaluation, "
                + "i.e. impersonation, is out of v1 scope)")
        void resolvesActorNotSubject() {
            PrincipalRef serviceActor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
            PrincipalRef userSubject = new PrincipalRef(PrincipalType.USER, "user-99", Map.of());
            PrincipalKey serviceKey = new PrincipalKey(PrincipalType.SERVICE, "svc-1");
            PrincipalAuthorityResolver resolver = fixedResolver(serviceKey, claimsWithRole(REQUIRED_ROLE));
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(rolePermittingAuthorizer(), resolver);
            SecurityContext ctx = deferredStyleCtx(serviceActor, userSubject, AuthorizationClaims.empty());

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertTrue(
                    decision.permitted(),
                    "the resolver must be queried with the SERVICE actor's key, never the USER subject's, "
                            + "for the permitting ROLE to be found");
        }
    }

    // --- fail-closed on resolver failure ---

    @Nested
    @DisplayName("resolverFailureDeniesWithReason")
    class ResolverFailureDeniesWithReason {

        @Test
        @DisplayName("a resolver failure denies with AUTHORITY_RESOLUTION_FAILED, still stamped LIVE_RESOLVED")
        void resolverFailureDeniesWithReason() {
            PrincipalAuthorityResolver resolver = key -> Future.failedFuture("principal unresolvable");
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(rolePermittingAuthorizer(), resolver);
            SecurityContext ctx = reconstructedCtx(AuthorizationClaims.empty());

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertFalse(decision.permitted(), "a resolver failure must fail closed to a deny");
            assertEquals(AuthzReasonCodes.AUTHORITY_RESOLUTION_FAILED, decision.reasonCode());
            assertEquals(
                    ReconstructedAuthorityMode.LIVE_RESOLVED.name(),
                    decision.safeAttributes().get(ReconstructedAuthorityResolvingAuthorizer.AUTHORITY_MODE_ATTRIBUTE));
        }
    }

    // --- narrowing still applies over the re-resolved claims ---

    @Nested
    @DisplayName("narrowingStillRemovesAuthority")
    class NarrowingStillRemovesAuthority {

        @Test
        @DisplayName("even when the resolver grants the role, a NarrowingAuthorizer's denying narrower still "
                + "denies — proving the narrower runs on the live-resolved evaluation context")
        void narrowingStillRemovesAuthority() {
            Authorizer alwaysPermitBase = fixedAuthorizer(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
            NarrowingAuthorizer narrowing = new NarrowingAuthorizer(alwaysPermitBase, Set.of(denyingNarrower()));
            PrincipalAuthorityResolver resolver = fixedResolver(PRINCIPAL, claimsWithRole(REQUIRED_ROLE));
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(narrowing, resolver);
            SecurityContext ctx = reconstructedCtx(AuthorizationClaims.empty());

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertFalse(decision.permitted(), "the installed narrower must still be able to remove authority");
        }

        private static AuthorizationNarrower denyingNarrower() {
            return new AuthorizationNarrower() {
                @Override
                public int priority() {
                    return 0;
                }

                @Override
                public String orderKey() {
                    return "always-deny-narrower";
                }

                @Override
                public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
                    return Future.succeededFuture(AuthorizationDecision.deny("NARROWED"));
                }

                @Override
                public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
                    return Optional.empty();
                }
            };
        }
    }

    // --- non-reconstructed passthrough ---

    @Nested
    @DisplayName("nonReconstructedContextUnaffected")
    class NonReconstructedContextUnaffected {

        @Test
        @DisplayName("a normal, live-authored context passes through to the inner decision unchanged, and the "
                + "resolver is never consulted")
        void nonReconstructedContextUnaffected() {
            AuthorizationDecision fixedDecision = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);
            Authorizer inner = fixedAuthorizer(fixedDecision);
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(inner, neverCalledResolver());
            SecurityContext ctx = liveCtx(AuthorizationClaims.empty());

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertSame(fixedDecision, decision, "a non-reconstructed context must pass the inner decision through");
        }
    }

    // --- typed marker, not the string attribute, is the trigger ---

    @Nested
    @DisplayName("triggersOnTypedContextNotStringMarker")
    class TriggersOnTypedContextNotStringMarker {

        @Test
        @DisplayName("a normal context carrying identity.reconstructed=true as a safeAttributes string but no "
                + "typed reconstruction() marker is treated as non-reconstructed passthrough")
        void triggersOnTypedContextNotStringMarker() {
            AuthorizationDecision fixedDecision = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);
            Authorizer inner = fixedAuthorizer(fixedDecision);
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(inner, neverCalledResolver());
            SecurityContext ctx = liveCtxWithStringMarker();

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertTrue(ctx.reconstruction().isEmpty(), "the stub context must carry no typed reconstruction marker");
            assertSame(
                    fixedDecision,
                    decision,
                    "a string-only identity.reconstructed attribute must not trigger Mode-2 resolution");
        }
    }

    // --- stale snapshot claims never authorize ---

    @Nested
    @DisplayName("staleSnapshotClaimsNeverAuthorize")
    class StaleSnapshotClaimsNeverAuthorize {

        @Test
        @DisplayName("a reconstructed context whose frozen authorization() carries the permitting role is still "
                + "denied when the resolver's live answer is empty (principal revoked) — the frozen snapshot "
                + "claims never authorize; only the live resolution governs")
        void staleSnapshotClaimsNeverAuthorize() {
            PrincipalAuthorityResolver resolver = fixedResolver(PRINCIPAL, AuthorizationClaims.empty());
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(rolePermittingAuthorizer(), resolver);
            // The frozen/stale snapshot claims carry the permitting role — if trusted, this would permit.
            SecurityContext ctx = reconstructedCtx(claimsWithRole(REQUIRED_ROLE));

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertFalse(
                    decision.permitted(),
                    "the resolver's live (empty) claims must govern, not the frozen snapshot's stale role claim");
        }
    }

    // --- Mode 3: a CAPTURED-marked reconstruction is never live-resolved ---

    @Nested
    @DisplayName("capturedMarkerNotLiveResolved")
    class CapturedMarkerNotLiveResolved {

        @Test
        @DisplayName("a CAPTURED-marked reconstructed context is evaluated as-is through inner — the resolver is "
                + "never called, and the decision is stamped authz.authority.mode=CAPTURED")
        void capturedMarkerNotLiveResolved() {
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(rolePermittingAuthorizer(), neverCalledResolver());
            // The context's own (captured) claims carry the permitting role — CAPTURED trusts them as-is.
            SecurityContext ctx = capturedCtx(claimsWithRole(REQUIRED_ROLE));

            AuthorizationDecision decision = await(authorizer.authorize(request(ctx)));

            assertTrue(decision.permitted(), "a CAPTURED context's own claims must govern the decision as-is");
            assertEquals(
                    ReconstructedAuthorityMode.CAPTURED.name(),
                    decision.safeAttributes().get(ReconstructedAuthorityResolvingAuthorizer.AUTHORITY_MODE_ATTRIBUTE));
        }
    }

    // --- Mode-2 rebuild preserves the incoming invocation origin ---

    @Nested
    @DisplayName("originPreservedThroughLiveResolveRebuild")
    class OriginPreservedThroughLiveResolveRebuild {

        @Test
        @DisplayName("the evaluation-only context rebuild for ATTRIBUTION_ONLY live resolution carries the "
                + "original request's InvocationOrigin through to the inner authorizer unchanged")
        void originPreservedThroughLiveResolveRebuild() {
            InvocationOrigin seededOrigin = InvocationOrigin.of("rest");
            PrincipalAuthorityResolver resolver = fixedResolver(PRINCIPAL, claimsWithRole(REQUIRED_ROLE));
            AuthorizationRequest[] captured = new AuthorizationRequest[1];
            Authorizer capturingInner = new Authorizer() {
                @Override
                public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                    captured[0] = request;
                    return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                }

                @Override
                public Future<AuthorizationDecision> authorize(
                        SecurityContext ctx, ActionRef action, ResourceRef resource) {
                    throw new AssertionError("3-arg authorize must not be used by this decorator's request path");
                }
            };
            ReconstructedAuthorityResolvingAuthorizer authorizer =
                    new ReconstructedAuthorityResolvingAuthorizer(capturingInner, resolver);
            SecurityContext ctx = reconstructedCtx(AuthorizationClaims.empty());
            AuthorizationRequest originalRequest =
                    new AuthorizationRequest(ctx, ACTION.value(), RESOURCE, seededOrigin, Map.of());

            await(authorizer.authorize(originalRequest));

            assertEquals(
                    seededOrigin,
                    captured[0].origin(),
                    "the rebuilt evaluation-only request must carry the original InvocationOrigin, not "
                            + "InvocationOrigin.unspecified()");
        }
    }

    // --- helpers ---

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "authorize() must return an already-succeeded future");
        return future.result();
    }

    private static AuthorizationRequest request(SecurityContext ctx) {
        return new AuthorizationRequest(ctx, ACTION.value(), RESOURCE, Map.of());
    }

    private static AuthorizationClaims claimsWithRole(String role) {
        return new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of())), Map.of());
    }

    private static PrincipalAuthorityResolver fixedResolver(PrincipalKey key, AuthorizationClaims claims) {
        return candidate -> {
            assertEquals(key, candidate, "resolver must be consulted with the reconstructed principal's key");
            return Future.succeededFuture(claims);
        };
    }

    /** A resolver that fails the test if invoked — used for non-reconstructed passthrough scenarios. */
    private static PrincipalAuthorityResolver neverCalledResolver() {
        return key -> {
            throw new AssertionError("PrincipalAuthorityResolver.resolve() must not be called in this scenario");
        };
    }

    private static Authorizer fixedAuthorizer(AuthorizationDecision decision) {
        return new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                return Future.succeededFuture(decision);
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                return Future.succeededFuture(decision);
            }
        };
    }

    /** An {@link Authorizer} test double that permits iff the context's live authorization() carries the ROLE. */
    private static Authorizer rolePermittingAuthorizer() {
        return new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                boolean hasRole = request.securityContext()
                        .authorization()
                        .valuesOf(AuthorityKind.ROLE)
                        .contains(REQUIRED_ROLE);
                return Future.succeededFuture(
                        hasRole
                                ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                                : AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING));
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                return authorize(new AuthorizationRequest(ctx, action.value(), resource, Map.of()));
            }
        };
    }

    private static SecurityContext reconstructedCtx(AuthorizationClaims claims) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), java.util.List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assembleReconstructed(
                identity,
                auth,
                claims,
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY));
    }

    private static SecurityContext capturedCtx(AuthorizationClaims claims) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), java.util.List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assembleReconstructed(
                identity,
                auth,
                claims,
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED));
    }

    /**
     * Builds a deferred-style reconstructed context whose actor and subject differ, mirroring
     * {@code DefaultIdentityReconstruction.deferredExecution}'s shape (executing service actor,
     * subject-of-record on {@code identity().subject()}).
     */
    private static SecurityContext deferredStyleCtx(
            PrincipalRef actor, PrincipalRef subject, AuthorizationClaims claims) {
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), java.util.List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assembleReconstructed(
                identity,
                auth,
                claims,
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY));
    }

    private static SecurityContext liveCtx(AuthorizationClaims claims) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), java.util.List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assemble(identity, auth, claims, Optional.empty());
    }

    private static SecurityContext liveCtxWithStringMarker() {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(),
                java.util.List.of(),
                Optional.empty(),
                Optional.empty(),
                Map.of("identity.reconstructed", "true"));
        return SecurityContexts.assemble(identity, auth, AuthorizationClaims.empty(), Optional.empty());
    }
}
