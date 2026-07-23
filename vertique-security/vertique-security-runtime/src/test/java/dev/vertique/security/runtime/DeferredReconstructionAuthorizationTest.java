// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.PrincipalKey;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.runtime.authz.DefaultActionRegistry;
import dev.vertique.security.runtime.authz.DefaultAuthorizer;
import dev.vertique.security.runtime.authz.DelegationEnforcementNarrower;
import dev.vertique.security.runtime.authz.InMemoryPolicyDefinitionSource;
import dev.vertique.security.runtime.authz.InMemoryPrincipalAuthorityResolver;
import dev.vertique.security.runtime.authz.InMemoryRolePolicyResolver;
import dev.vertique.security.runtime.authz.NarrowingAuthorizer;
import dev.vertique.security.runtime.authz.ReconstructedAuthorityResolvingAuthorizer;
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
import org.junit.jupiter.api.Test;

/**
 * Composition tests for the security-critical seam between verified deferred reconstruction and the
 * complete Mode-2 authorization chain. Component-level tests already cover snapshot forgery,
 * freshness, resolver behavior, delegation narrowing, and the default policy engine independently;
 * these tests pin the actor/subject contract across their real implementations.
 */
class DeferredReconstructionAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");
    private static final Map<String, String> KEYSET = Map.of("k1", "integration-test-signing-key-material");
    private static final PrincipalRef CAPTURED_USER = new PrincipalRef(PrincipalType.USER, "user-42", Map.of());
    private static final PrincipalRef EXECUTING_SERVICE =
            new PrincipalRef(PrincipalType.SERVICE, "report-executor", Map.of());
    private static final PrincipalKey SERVICE_KEY = new PrincipalKey(EXECUTING_SERVICE.type(), EXECUTING_SERVICE.id());
    private static final PrincipalKey USER_KEY = new PrincipalKey(CAPTURED_USER.type(), CAPTURED_USER.id());
    private static final ActionRef ACTION = ActionRef.of("reports", "export", "run");
    private static final ResourceRef RESOURCE = new ResourceRef("report", "monthly", Map.of());
    private static final String ROLE = "report-executor";
    private static final String POLICY = "report-execution";
    private static final DurableCarrierDescriptor CARRIER = new DurableCarrierDescriptor(
            "job-42", new DurableTarget("delayed-job", "reports.export", Optional.empty()));

    @Test
    @DisplayName("deferred reconstruction resolves the executing service, not the attributed user")
    void deferredReconstructionResolvesServiceActorNotSubject() {
        AuthorizationClaims serviceAuthority = claimsWithRole();
        ReconstructedAuthorityResolvingAuthorizer authorizer =
                authorizer(Map.of(SERVICE_KEY, serviceAuthority, USER_KEY, AuthorizationClaims.empty()));

        SecurityContext reconstructed = reconstruct(snapshotWithClaims(List.of()), codec());
        AuthorizationDecision decision = await(authorizer.authorize(request(reconstructed)));

        assertEquals(EXECUTING_SERVICE, reconstructed.identity().actor());
        assertEquals(CAPTURED_USER, reconstructed.identity().subject().orElseThrow());
        assertTrue(decision.permitted(), "the service actor's live authority must govern");
    }

    @Test
    @DisplayName("revoking the executing service during the pause denies despite captured authority")
    void revokedServiceDuringPauseDeniesLive() {
        ReconstructedAuthorityResolvingAuthorizer authorizer =
                authorizer(Map.of(SERVICE_KEY, AuthorizationClaims.empty(), USER_KEY, claimsWithRole()));

        SecurityContext reconstructed =
                reconstruct(snapshotWithClaims(List.copyOf(claimsWithRole().claims())), codec());
        AuthorizationDecision decision = await(authorizer.authorize(request(reconstructed)));

        assertFalse(
                decision.permitted(),
                "captured or subject authority must not survive revocation of the executing service");
        assertEquals(AuthzReasonCodes.ROLE_MISSING, decision.reasonCode());
    }

    private static ReconstructedAuthorityResolvingAuthorizer authorizer(
            Map<PrincipalKey, AuthorizationClaims> liveAuthority) {
        DefaultActionRegistry registry = new DefaultActionRegistry(Set.of(actionContributor()));
        PolicyDefinition policy = new PolicyDefinition(
                POLICY, List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(ACTION.value())))));
        DefaultAuthorizer base = new DefaultAuthorizer(
                registry,
                new InMemoryPolicyDefinitionSource(List.of(policy)).withRegistry(registry),
                new InMemoryRolePolicyResolver(Map.of(ROLE, List.of(POLICY))));
        NarrowingAuthorizer narrowed = new NarrowingAuthorizer(
                base, Set.of(new DelegationEnforcementNarrower(new InMemoryDelegationGrantValidator(List.of()))));
        return new ReconstructedAuthorityResolvingAuthorizer(
                narrowed, new InMemoryPrincipalAuthorityResolver(liveAuthority));
    }

    private static ActionContributor actionContributor() {
        return () -> List.of(new ActionDefinition(ACTION));
    }

    private static SecurityContext reconstruct(IdentitySnapshot snapshot, IdentitySnapshotCodec codec) {
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executor = SecurityIdentity.service(EXECUTING_SERVICE);
        return reconstruction.deferredExecution(executor, snapshot, CARRIER);
    }

    private static IdentitySnapshot snapshotWithClaims(List<AuthorityClaim> capturedClaims) {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                CAPTURED_USER,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthMethodKind.JWT.name(),
                NOW.minusSeconds(60),
                Optional.empty(),
                capturedClaims,
                "rest",
                NOW.minusSeconds(30));
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                new SnapshotCarrierBinding(CARRIER.carrierId(), CARRIER.target()),
                NOW.minusSeconds(20),
                NOW.plus(Duration.ofHours(1)),
                new SnapshotIntegrity("HmacSHA256", "k1", "unsigned"));
        return codec.decode(codec.encode(unsigned));
    }

    private static IdentitySnapshotCodec codec() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new IdentitySnapshotCodec(
                new SnapshotHmac(KEYSET, "k1"),
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), clock));
    }

    private static AuthorizationClaims claimsWithRole() {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, ROLE, "", "", "test", Map.of());
        return new AuthorizationClaims(Set.of(claim), Map.of());
    }

    private static AuthorizationRequest request(SecurityContext context) {
        return new AuthorizationRequest(context, ACTION.value(), RESOURCE, Map.of());
    }

    private static AuthorizationDecision await(Future<AuthorizationDecision> future) {
        assertTrue(future.succeeded(), "the in-memory authorization chain must complete synchronously");
        return future.result();
    }
}
