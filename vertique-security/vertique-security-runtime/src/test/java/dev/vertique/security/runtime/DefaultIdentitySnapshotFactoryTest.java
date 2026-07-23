// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.verification.CustomVerificationSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultIdentitySnapshotFactory}.
 *
 * <p>Verifies that {@link DefaultIdentitySnapshotFactory#capture(SecurityContext)} preserves the
 * full identity dimension (actor, subject, delegation, client, typed claims) of a live
 * {@link SecurityContext}.
 *
 * <p>Per the schema-v2 content/envelope split, {@code capture(...)} returns
 * {@link IdentitySnapshotContent} — carrier binding, temporal bounds, and the integrity envelope
 * belong to the durable encoder, so this test asserts only over the captured identity dimension. The
 * token-leakage tests wrap the content in a minimal envelope ({@code envelope(...)}) to exercise the
 * codec's encode path.
 */
class DefaultIdentitySnapshotFactoryTest {

    @Test
    @DisplayName("capture(live) captures the full delegated identity dimension")
    void identitySnapshotCapturesDelegatedContext() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
        DelegationContext delegation = new DelegationContext("psd2-pis", "grant-42", Optional.empty(), Map.of());
        ClientRef client = new ClientRef("client-1", "jwt-azp", Map.of());
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.of(delegation), Optional.of(client));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "test", Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(claim), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertNotNull(snapshot);
        assertEquals(actor, snapshot.actor());
        assertTrue(snapshot.subject().isPresent());
        assertEquals(subject, snapshot.subject().get());
        assertTrue(snapshot.delegation().isPresent());
        assertEquals(delegation.kind(), snapshot.delegation().get().kind());
        assertTrue(snapshot.delegation().get().authorityId().isPresent());
        assertEquals(
                delegation.authorityId(),
                snapshot.delegation().get().authorityId().get());
        assertTrue(snapshot.client().isPresent());
        assertEquals(client, snapshot.client().get());
        assertTrue(snapshot.authorizationClaims().contains(claim));
        assertNotNull(snapshot.capturedAt());
    }

    @Test
    @DisplayName("authenticatedAt uses the decisive (first) evidence entry's verifiedAt when assurance is absent")
    void authenticatedAtUsesDecisiveEvidenceVerifiedAt() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());

        Instant verifiedAt = Instant.parse("2026-07-01T10:00:00Z");
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                DefaultAuthMethod.custom("password"),
                Optional.empty(),
                verifiedAt,
                Optional.empty(),
                new CustomVerificationSource("test", Map.of()),
                Map.of());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(evidence), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(verifiedAt, snapshot.authenticatedAt());
    }

    @Test
    @DisplayName("authenticatedAt prefers assurance authTime over evidence verifiedAt when both are present")
    void authenticatedAtPrefersAssuranceAuthTime() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());

        Instant verifiedAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant authTime = Instant.parse("2026-06-15T08:30:00Z");
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                DefaultAuthMethod.custom("password"),
                Optional.empty(),
                verifiedAt,
                Optional.empty(),
                new CustomVerificationSource("test", Map.of()),
                Map.of());
        AuthenticationAssurance assurance =
                new AuthenticationAssurance(Optional.empty(), Set.of(), Optional.of(authTime), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"),
                List.of(evidence),
                Optional.of(assurance),
                Optional.empty(),
                Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(authTime, snapshot.authenticatedAt());
    }

    @Test
    @DisplayName("authenticatedAt falls back to capturedAt when there is no evidence and no assurance")
    void authenticatedAtFallsBackToCapturedAtWithoutEvidence() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());

        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        Instant testStart = Instant.now();
        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertFalse(
                snapshot.authenticatedAt().isBefore(testStart),
                "authenticatedAt must fall back to the capture instant when there is no evidence or assurance");
    }

    @Test
    @DisplayName("authenticatedAt reads the identity.reconstructed.authenticatedAt marker on a chained "
            + "recapture when there is no assurance or evidence")
    void authenticatedAtSurvivesChainedRecapture() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());

        Instant markerAuthenticatedAt = Instant.parse("2026-01-01T00:00:00Z");
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("jwt"),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Map.of("identity.reconstructed.authenticatedAt", markerAuthenticatedAt.toString()));
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        Instant testStart = Instant.now();
        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(
                markerAuthenticatedAt,
                snapshot.authenticatedAt(),
                "a chained recapture must carry forward the original authenticatedAt, not the new capture instant");
        assertTrue(
                snapshot.authenticatedAt().isBefore(testStart),
                "the resolved authenticatedAt must be the marker's past instant, not the (later) capture instant");
    }

    @Test
    @DisplayName("authenticatedAt prefers assurance authTime over the identity.reconstructed.authenticatedAt marker")
    void authenticatedAtAssuranceWinsOverReconstructedMarker() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());

        Instant authTime = Instant.parse("2026-02-02T00:00:00Z");
        Instant markerAuthenticatedAt = Instant.parse("2026-01-01T00:00:00Z");
        AuthenticationAssurance assurance =
                new AuthenticationAssurance(Optional.empty(), Set.of(), Optional.of(authTime), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("jwt"),
                List.of(),
                Optional.of(assurance),
                Optional.empty(),
                Map.of("identity.reconstructed.authenticatedAt", markerAuthenticatedAt.toString()));
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(
                authTime,
                snapshot.authenticatedAt(),
                "assurance.authTime must win over the reconstructed-marker attribute when both are present");
    }

    @Test
    @DisplayName("capture(live) projects free-form attributes out of actor, subject, client, and claims")
    void captureProjectsFreeFormAttributesOut() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(
                PrincipalType.SERVICE, "svc-1", Map.of("raw.token", "tok-SECRET-123", "tenant", "acme"));
        PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("x", "y"));
        ClientRef client = new ClientRef("client-1", "jwt-azp", Map.of("c", "v"));
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "test", Map.of("a", "b"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.of(client));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(claim), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertTrue(
                snapshot.actor().attributes().isEmpty(),
                "actor attributes must be projected out when none are allowlisted");
        assertEquals(PrincipalType.SERVICE, snapshot.actor().type());
        assertEquals("svc-1", snapshot.actor().id());

        assertTrue(snapshot.subject().isPresent());
        assertTrue(
                snapshot.subject().get().attributes().isEmpty(),
                "subject attributes must be projected out when none are allowlisted");
        assertEquals(PrincipalType.USER, snapshot.subject().get().type());
        assertEquals("user-1", snapshot.subject().get().id());

        assertTrue(snapshot.client().isPresent());
        assertTrue(
                snapshot.client().get().attributes().isEmpty(),
                "client attributes must be projected out when none are allowlisted");
        assertEquals("client-1", snapshot.client().get().clientId());
        assertEquals("jwt-azp", snapshot.client().get().source());

        assertEquals(1, snapshot.authorizationClaims().size());
        AuthorityClaim projectedClaim = snapshot.authorizationClaims().get(0);
        assertTrue(
                projectedClaim.attributes().isEmpty(),
                "claim attributes must be projected out when none are allowlisted");
        assertEquals(AuthorityKind.ROLE, projectedClaim.kind());
        assertEquals("admin", projectedClaim.value());
        assertEquals("idp", projectedClaim.issuer());
        assertEquals("aud", projectedClaim.audience());
        assertEquals("test", projectedClaim.source());
    }

    @Test
    @DisplayName("capture(live) retains the framework-owned system.reason attribute")
    void captureRetainsSystemReasonAttribute() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        SecurityContext ctx = SecurityContexts.system(SystemIdentities.scheduledJob("nightly-report"));

        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(Map.of("system.reason", "nightly-report"), snapshot.actor().attributes());
    }

    @Test
    @DisplayName("the encoded snapshot carries no free-form attribute bytes (no token leakage)")
    void encodedSnapshotCarriesNoTokenBytes() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(
                PrincipalType.SERVICE, "svc-1", Map.of("raw.token", "tok-SECRET-123", "tenant", "acme"));
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac);
        byte[] encoded = codec.encode(envelope(snapshot));
        String json = new String(encoded, StandardCharsets.UTF_8);

        assertFalse(json.contains("raw.token"), "encoded snapshot must not carry the raw.token attribute key");
        assertFalse(json.contains("tok-SECRET-123"), "encoded snapshot must not carry the raw token value");
        assertTrue(json.contains("svc-1"), "encoded snapshot must still carry the actor id");
    }

    @Test
    @DisplayName("capture(live) drops system.reason from a non-SYSTEM actor")
    void captureDropsSystemReasonFromNonSystemActor() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("system.reason", "tok-SECRET-usr"));
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertTrue(
                snapshot.actor().attributes().isEmpty(),
                "system.reason must not survive capture on a non-SYSTEM actor");
    }

    @Test
    @DisplayName("capture(live) drops system.reason from the subject unconditionally")
    void captureDropsSystemReasonFromSubject() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        PrincipalRef subject =
                new PrincipalRef(PrincipalType.USER, "user-1", Map.of("system.reason", "tok-SECRET-sub"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertTrue(
                snapshot.subject().orElseThrow().attributes().isEmpty(),
                "system.reason must never survive capture on the subject dimension");
    }

    @Test
    @DisplayName("capture(live) drops system.reason from the client and every claim unconditionally")
    void captureDropsSystemReasonFromClientAndClaims() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of());
        ClientRef client = new ClientRef("client-1", "jwt-azp", Map.of("system.reason", "tok-SECRET-cli"));
        AuthorityClaim claim = new AuthorityClaim(
                AuthorityKind.ROLE, "admin", "idp", "aud", "test", Map.of("system.reason", "tok-SECRET-clm"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.of(client));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(claim), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertTrue(
                snapshot.client().orElseThrow().attributes().isEmpty(),
                "system.reason must never survive capture on the client dimension");
        assertEquals(1, snapshot.authorizationClaims().size());
        assertTrue(
                snapshot.authorizationClaims().get(0).attributes().isEmpty(),
                "system.reason must never survive capture on a claim's attributes");
    }

    @Test
    @DisplayName("capture(live) drops an unbounded or non-String system.reason value from a SYSTEM actor")
    void captureDropsUnboundedSystemReasonFromSystemActor() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());

        PrincipalRef oversizedActor =
                new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", "x".repeat(300)));
        IdentitySnapshotContent oversizedSnapshot = factory.capture(assembleWithActor(oversizedActor));
        assertTrue(
                oversizedSnapshot.actor().attributes().isEmpty(),
                "an over-length system.reason value must be dropped, not retained");

        PrincipalRef nonStringActor = new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", 42));
        IdentitySnapshotContent nonStringSnapshot = factory.capture(assembleWithActor(nonStringActor));
        assertTrue(
                nonStringSnapshot.actor().attributes().isEmpty(),
                "a non-String system.reason value must be dropped, not retained");
    }

    @Test
    @DisplayName("the encoded snapshot carries no system.reason smuggled through non-SYSTEM-actor dimensions")
    void encodedSnapshotCarriesNoSystemReasonSmuggling() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("system.reason", "tok-SECRET-usr"));
        PrincipalRef subject =
                new PrincipalRef(PrincipalType.USER, "user-2", Map.of("system.reason", "tok-SECRET-sub"));
        ClientRef client = new ClientRef("client-1", "jwt-azp", Map.of("system.reason", "tok-SECRET-cli"));
        AuthorityClaim claim = new AuthorityClaim(
                AuthorityKind.ROLE, "admin", "idp", "aud", "test", Map.of("system.reason", "tok-SECRET-clm"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.of(client));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(claim), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac);
        byte[] encoded = codec.encode(envelope(snapshot));
        String json = new String(encoded, StandardCharsets.UTF_8);

        assertFalse(json.contains("tok-SECRET-usr"), "encoded snapshot must not carry the non-SYSTEM actor's value");
        assertFalse(json.contains("tok-SECRET-sub"), "encoded snapshot must not carry the subject's value");
        assertFalse(json.contains("tok-SECRET-cli"), "encoded snapshot must not carry the client's value");
        assertFalse(json.contains("tok-SECRET-clm"), "encoded snapshot must not carry the claim's value");
    }

    @Test
    @DisplayName("capture(live) drops system.reason from a SYSTEM-typed subject (the gate applies to the actor only)")
    void captureDropsSystemReasonFromSystemTypedSubject() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor = new PrincipalRef(PrincipalType.USER, "user-1", Map.of());
        PrincipalRef subject =
                new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", "sub-reason"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertTrue(
                snapshot.subject().orElseThrow().attributes().isEmpty(),
                "system.reason must never survive capture on the subject dimension, even for a SYSTEM-typed subject");
    }

    @Test
    @DisplayName("capture(live) retains the SYSTEM actor's system.reason while dropping the subject's attributes")
    void captureRetainsActorReasonWhileDroppingSubjectAttributes() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());
        PrincipalRef actor =
                new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", "nightly-report"));
        PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "user-1", Map.of("x", "y"));
        SecurityIdentity identity =
                new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.empty());
        IdentitySnapshotContent snapshot = factory.capture(ctx);

        assertEquals(Map.of("system.reason", "nightly-report"), snapshot.actor().attributes());
        assertTrue(
                snapshot.subject().orElseThrow().attributes().isEmpty(),
                "subject attributes must be projected out even when the actor's system.reason is retained");
    }

    @Test
    @DisplayName("capture(live) retains system.reason at exactly 256 chars and drops it at exactly 257 chars")
    void systemReasonBoundaryAt256Chars() {
        DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(new DefaultContextHolder());

        PrincipalRef boundaryActor =
                new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", "x".repeat(256)));
        IdentitySnapshotContent boundarySnapshot = factory.capture(assembleWithActor(boundaryActor));
        assertEquals(
                Map.of("system.reason", "x".repeat(256)),
                boundarySnapshot.actor().attributes());

        PrincipalRef overBoundaryActor =
                new PrincipalRef(PrincipalType.SYSTEM, "system:x", Map.of("system.reason", "x".repeat(257)));
        IdentitySnapshotContent overBoundarySnapshot = factory.capture(assembleWithActor(overBoundaryActor));
        assertTrue(
                overBoundarySnapshot.actor().attributes().isEmpty(),
                "a system.reason value one character over the 256-char bound must be dropped");
    }

    /**
     * Assembles a minimal {@link SecurityContext} carrying only the given actor, for the
     * SYSTEM-actor value-gate tests where the subject/client/claim dimensions are irrelevant.
     *
     * @param actor the actor principal ref to assemble the context around
     * @return a live context wrapping {@code actor} with no subject, delegation, client, or claims
     */
    private static SecurityContext assembleWithActor(PrincipalRef actor) {
        SecurityIdentity identity = new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());
        return SecurityContexts.assemble(identity, auth, claims, Optional.empty());
    }

    /**
     * Wraps captured {@link IdentitySnapshotContent} in a minimal schema-v2 {@link IdentitySnapshot}
     * envelope so it can be signed by the codec — the durable encoder does this in production; here it
     * lets a token-leakage test assert over the encoded bytes.
     *
     * @param content the captured content to wrap
     * @return a to-be-signed envelope around {@code content}
     */
    private static IdentitySnapshot envelope(IdentitySnapshotContent content) {
        Instant issuedAt = Instant.now();
        return new IdentitySnapshot(
                2,
                content,
                new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty())),
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));
    }
}
