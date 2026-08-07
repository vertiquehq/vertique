// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.IdentityReconstructionException;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.verification.CustomVerificationSource;
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
 * Unit tests for {@link DefaultIdentityReconstruction} against schema v2 (PRD-ID-002 §14.6 amendment
 * A9). Reconstruction is the trust boundary (FR-ID-CA-008): it re-verifies the snapshot's HMAC and its
 * F5 carrier binding before minting a {@link SecurityContext}. Every test that needs a genuinely valid
 * signed snapshot builds one via {@link #signedSnapshot} — an unsigned envelope round-tripped through
 * {@code codec.encode}/{@code codec.decode} so the returned snapshot carries a real, verifiable tag.
 */
class DefaultIdentityReconstructionTest {

    private static final Map<String, String> KEYSET = Map.of("k1", "super-secret-signing-key-material");
    private static final String ACTIVE_KEY_ID = "k1";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));
    private static final DelegationSummary DELEGATION = new DelegationSummary("on-behalf-of", Optional.of("grant-7"));

    /**
     * The same grant-backed scheme captured <em>without</em> a grant identifier — the shape that
     * drives {@code DefaultIdentityReconstruction.toDelegationContext}'s absent-id fallback, which
     * {@link #DELEGATION} (always carrying an id) never reaches.
     */
    private static final DelegationSummary UNIDENTIFIED_DELEGATION =
            new DelegationSummary("on-behalf-of", Optional.empty());

    private static final ClientRef CLIENT = new ClientRef("client-abc", "jwt-azp", Map.of("app", "mobile"));
    private static final SnapshotCarrierBinding CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));

    /** The trusted receive-side expected carrier that matches {@link #CARRIER}. */
    private static DurableCarrierDescriptor matching(IdentitySnapshot snapshot) {
        return new DurableCarrierDescriptor(
                snapshot.carrier().carrierId(), snapshot.carrier().target());
    }

    @Test
    @DisplayName("resumeAsPrincipal restores the full identity structure as-is when the carrier matches")
    void resumeRestoresFullStructure() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);

        IdentitySnapshot snapshot = signedSnapshot(
                codec, ACTOR, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.of(CLIENT), List.of());

        SecurityContext ctx = reconstruction.resumeAsPrincipal(snapshot, matching(snapshot));

        assertEquals(ACTOR, ctx.identity().actor());
        assertTrue(ctx.identity().subject().isPresent());
        assertEquals(SUBJECT, ctx.identity().subject().get());
        assertTrue(ctx.identity().delegation().isPresent());
        assertEquals(DELEGATION.kind(), ctx.identity().delegation().get().kind());
        assertTrue(DELEGATION.authorityId().isPresent());
        assertEquals(
                DELEGATION.authorityId().get(),
                ctx.identity().delegation().get().authorityId());
        assertTrue(ctx.identity().client().isPresent());
        assertEquals(CLIENT, ctx.identity().client().get());
    }

    @Test
    @DisplayName("resumeAsPrincipal states the absence of a captured grant id rather than reusing the "
            + "deferred-execution marker")
    void resumeStatesAbsentCapturedAuthorityId() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);

        IdentitySnapshot snapshot = signedSnapshot(
                codec,
                ACTOR,
                Optional.of(SUBJECT),
                Optional.of(UNIDENTIFIED_DELEGATION),
                Optional.of(CLIENT),
                List.of());

        SecurityContext resumed = reconstruction.resumeAsPrincipal(snapshot, matching(snapshot));

        assertTrue(resumed.identity().delegation().isPresent());
        DelegationContext delegation = resumed.identity().delegation().orElseThrow();
        assertEquals(
                UNIDENTIFIED_DELEGATION.kind(),
                delegation.kind(),
                "the captured delegation scheme must be preserved verbatim");
        assertEquals(
                "no-captured-authority-id",
                delegation.authorityId(),
                "a Mode-1 resume whose captured summary carried no grant id must state the absence, never "
                        + "reuse the deferred-execution literal a consumer could mistake for a real grant id");
    }

    @Test
    @DisplayName("carrier mismatch fails closed on both entry points, never minting a context")
    void carrierMismatchFailsClosed() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentitySnapshot snapshot =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());

        // A different row-carrier than the one the snapshot was signed for — a replay/transplant.
        DurableCarrierDescriptor wrongCarrier =
                new DurableCarrierDescriptor("other-carrier", new DurableTarget("outbox", "orders", Optional.empty()));

        assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeAsPrincipal(snapshot, wrongCarrier),
                "resumeAsPrincipal must fail closed when the snapshot's carrier does not match the expected carrier");
        assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.deferredExecution(executingService, snapshot, wrongCarrier),
                "deferredExecution must fail closed when the snapshot's carrier does not match the expected carrier");

        // A same carrierId but different target is likewise a mismatch.
        DurableCarrierDescriptor wrongTarget = new DurableCarrierDescriptor(
                snapshot.carrier().carrierId(), new DurableTarget("kafka", "orders", Optional.empty()));
        assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeAsPrincipal(snapshot, wrongTarget),
                "a matching carrierId but mismatched target must still fail closed");
    }

    @Test
    @DisplayName("carrier match reconstructs the deferred-execution context")
    void carrierMatchReconstructs() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentitySnapshot snapshot =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());

        SecurityContext deferred = reconstruction.deferredExecution(executingService, snapshot, matching(snapshot));

        assertEquals(executingService.actor(), deferred.identity().actor());
        assertTrue(deferred.identity().subject().isPresent());
        assertEquals(SUBJECT, deferred.identity().subject().get());
    }

    @Test
    @DisplayName("deferredExecution uses the subject-of-record: snapshot subject when present, else snapshot actor")
    void deferredUsesSubjectOfRecord() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentitySnapshot withSubject =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());
        SecurityContext deferredWithSubject =
                reconstruction.deferredExecution(executingService, withSubject, matching(withSubject));

        assertEquals(executingService.actor(), deferredWithSubject.identity().actor());
        assertTrue(deferredWithSubject.identity().subject().isPresent());
        assertEquals(SUBJECT, deferredWithSubject.identity().subject().get());

        IdentitySnapshot withoutSubject =
                signedSnapshot(codec, ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        SecurityContext deferredWithoutSubject =
                reconstruction.deferredExecution(executingService, withoutSubject, matching(withoutSubject));

        assertEquals(executingService.actor(), deferredWithoutSubject.identity().actor());
        assertTrue(deferredWithoutSubject.identity().subject().isPresent());
        assertEquals(ACTOR, deferredWithoutSubject.identity().subject().get());
    }

    @Test
    @DisplayName("deferredExecution preserves the captured delegation lineage in the framework-mediated context")
    void deferredExecutionPreservesCapturedDelegationLineage() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentitySnapshot withDelegation = signedSnapshot(
                codec, ACTOR, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.of(CLIENT), List.of());
        SecurityContext deferred =
                reconstruction.deferredExecution(executingService, withDelegation, matching(withDelegation));

        assertTrue(deferred.identity().delegation().isPresent());
        DelegationContext delegation = deferred.identity().delegation().orElseThrow();

        assertEquals("deferred-execution", delegation.authorityId());
        assertNotEquals(
                withDelegation.content().originSummary(),
                delegation.authorityId(),
                "the deferred authorityId must be the framework constant, never the origin summary");

        assertEquals("on-behalf-of", delegation.attributes().get("delegation.captured.kind"));
        assertEquals("grant-7", delegation.attributes().get("delegation.captured.authorityId"));

        IdentitySnapshot withoutDelegation =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());
        SecurityContext deferredNoDelegation =
                reconstruction.deferredExecution(executingService, withoutDelegation, matching(withoutDelegation));

        assertTrue(deferredNoDelegation.identity().delegation().isPresent());
        DelegationContext plain = deferredNoDelegation.identity().delegation().orElseThrow();
        assertEquals("deferred-execution", plain.authorityId());
        assertFalse(
                plain.attributes().containsKey("delegation.captured.kind"),
                "a snapshot with no captured delegation carries no captured-lineage attributes");
    }

    @Test
    @DisplayName("deferredExecution rejects an executing service whose actor is neither SYSTEM nor SERVICE")
    void deferredExecutionRejectsNonServiceActor() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        IdentitySnapshot snapshot =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());
        DurableCarrierDescriptor carrier = matching(snapshot);

        SecurityIdentity userExecutor = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        IllegalArgumentException userFailure = assertThrows(
                IllegalArgumentException.class,
                () -> reconstruction.deferredExecution(userExecutor, snapshot, carrier));
        assertTrue(
                userFailure.getMessage().contains("USER"), "the rejection message must name the offending actor type");

        IllegalArgumentException anonFailure = assertThrows(
                IllegalArgumentException.class,
                () -> reconstruction.deferredExecution(SecurityIdentity.anonymous(), snapshot, carrier));
        assertTrue(
                anonFailure.getMessage().contains("ANONYMOUS"),
                "the rejection message must name the offending actor type");

        SecurityIdentity serviceExecutor =
                SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "svc-1", Map.of()));
        SecurityContext deferred = reconstruction.deferredExecution(serviceExecutor, snapshot, carrier);
        assertEquals(serviceExecutor.actor(), deferred.identity().actor());
    }

    @Test
    @DisplayName("reconstructed contexts carry the marker, empty evidence, and the snapshot's method kind")
    void markerAndEmptyEvidence() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.internal("resume-check");

        IdentitySnapshot snapshot =
                signedSnapshot(codec, ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        DurableCarrierDescriptor carrier = matching(snapshot);

        SecurityContext resumed = reconstruction.resumeAsPrincipal(snapshot, carrier);
        SecurityContext deferred = reconstruction.deferredExecution(executingService, snapshot, carrier);

        assertTrue(IdentityReconstruction.isReconstructed(resumed));
        assertTrue(IdentityReconstruction.isReconstructed(deferred));

        assertEquals("RESUME", resumed.authentication().safeAttributes().get("identity.reconstructed.mode"));
        assertEquals("DEFERRED", deferred.authentication().safeAttributes().get("identity.reconstructed.mode"));

        assertEquals("true", resumed.authentication().safeAttributes().get("identity.reconstructed"));
        assertEquals(
                snapshot.content().capturedAt().toString(),
                resumed.authentication().safeAttributes().get("identity.reconstructed.capturedAt"));

        assertEquals(
                snapshot.content().authenticatedAt().toString(),
                resumed.authentication().safeAttributes().get("identity.reconstructed.authenticatedAt"));
        assertEquals(
                snapshot.content().authenticatedAt().toString(),
                deferred.authentication().safeAttributes().get("identity.reconstructed.authenticatedAt"));

        assertTrue(resumed.authentication().evidence().isEmpty());
        assertTrue(deferred.authentication().evidence().isEmpty());

        assertEquals(
                snapshot.content().authenticationMethodKind(),
                resumed.authentication().primaryMethod().id());
        assertEquals(
                snapshot.content().authenticationMethodKind(),
                deferred.authentication().primaryMethod().id());
    }

    @Test
    @DisplayName("reconstructed context carries no snapshot claims as current authority (both entry points)")
    void reconstructedContextCarriesNoSnapshotClaimsAsAuthority() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        AuthorityClaim snapshotClaim =
                new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        IdentitySnapshot snapshot = signedSnapshot(
                codec,
                ACTOR,
                Optional.of(SUBJECT),
                Optional.of(DELEGATION),
                Optional.of(CLIENT),
                List.of(snapshotClaim));
        DurableCarrierDescriptor carrier = matching(snapshot);

        assertTrue(
                snapshot.content().authorizationClaims().contains(snapshotClaim),
                "the snapshot retains its captured authorization claims as attribution");

        SecurityContext resumed = reconstruction.resumeAsPrincipal(snapshot, carrier);
        SecurityContext deferred = reconstruction.deferredExecution(executingService, snapshot, carrier);

        assertTrue(
                resumed.authorization().claims().isEmpty(),
                "resumeAsPrincipal must not present the snapshot's claims as current authority");
        assertTrue(
                deferred.authorization().claims().isEmpty(),
                "deferredExecution must not present the snapshot's claims as current authority");
        assertEquals(AuthorizationClaims.empty(), resumed.authorization());
        assertEquals(AuthorizationClaims.empty(), deferred.authorization());

        assertEquals(ACTOR, resumed.identity().actor());
        assertTrue(resumed.identity().subject().isPresent());
        assertEquals(SUBJECT, resumed.identity().subject().get());
        assertTrue(resumed.identity().delegation().isPresent());
        assertEquals(DELEGATION.kind(), resumed.identity().delegation().get().kind());

        assertEquals(executingService.actor(), deferred.identity().actor());
        assertTrue(deferred.identity().subject().isPresent());
        assertEquals(SUBJECT, deferred.identity().subject().get());
        assertTrue(deferred.identity().delegation().isPresent());
    }

    @Test
    @DisplayName("a tampered snapshot integrity tag fails closed on both entry points")
    void tamperedSnapshotFailsClosed() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.internal("resume-check");

        IdentitySnapshot valid =
                signedSnapshot(codec, ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        IdentitySnapshot tampered = new IdentitySnapshot(
                valid.schemaVersion(),
                valid.content(),
                valid.carrier(),
                valid.issuedAt(),
                valid.expiresAt(),
                new SnapshotIntegrity(
                        valid.integrity().algorithm(),
                        valid.integrity().keyId(),
                        valid.integrity().tag() + "x"));
        DurableCarrierDescriptor carrier = matching(valid);

        IdentityReconstructionException resumeFailure = assertThrows(
                IdentityReconstructionException.class, () -> reconstruction.resumeAsPrincipal(tampered, carrier));
        assertEquals(
                SnapshotDegradationReason.BAD_HMAC,
                resumeFailure.reason(),
                "a tampered integrity tag must surface reason BAD_HMAC");

        IdentityReconstructionException deferredFailure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.deferredExecution(executingService, tampered, carrier));
        assertEquals(
                SnapshotDegradationReason.BAD_HMAC,
                deferredFailure.reason(),
                "a tampered integrity tag must surface reason BAD_HMAC");
    }

    @Test
    @DisplayName("a snapshot signed under an unknown keyId fails closed with reason UNKNOWN_KEY")
    void unknownKeyIdFailsClosedWithReason() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);

        IdentitySnapshot valid =
                signedSnapshot(codec, ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        IdentitySnapshot unknownKeyed = new IdentitySnapshot(
                valid.schemaVersion(),
                valid.content(),
                valid.carrier(),
                valid.issuedAt(),
                valid.expiresAt(),
                new SnapshotIntegrity(
                        valid.integrity().algorithm(),
                        "no-such-key",
                        valid.integrity().tag()));

        IdentityReconstructionException failure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeAsPrincipal(unknownKeyed, matching(unknownKeyed)));
        assertEquals(
                SnapshotDegradationReason.UNKNOWN_KEY,
                failure.reason(),
                "an integrity envelope naming an unconfigured keyId must surface reason UNKNOWN_KEY");
    }

    @Test
    @DisplayName("resumeAsPrincipal fails closed for a validly-signed snapshot that is now past its effective "
            + "expiry, even though it was fresh when originally decoded")
    void expiredSnapshotFailsClosedOnResume() {
        Instant signedAt = Instant.parse("2026-07-01T11:00:00Z");
        IdentitySnapshotCodec signingCodec = fixedClockCodec(signedAt);
        IdentitySnapshot snapshot = signedSnapshot(
                signingCodec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of(), signedAt);

        // The snapshot was fresh when decoded via signingCodec above. Reconstruction now runs against a
        // codec whose "current" clock is 2h later — well past the snapshot's 1h signed window plus the
        // tolerated skew — modeling a typed snapshot retained in memory past its effective expiry.
        IdentitySnapshotCodec verifyingCodec = fixedClockCodec(signedAt.plus(Duration.ofHours(2)));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(verifyingCodec);

        IdentityReconstructionException failure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeAsPrincipal(snapshot, matching(snapshot)),
                "resumeAsPrincipal must fail closed for a snapshot that is now past its effective expiry, "
                        + "not merely on integrity failure");
        assertEquals(
                SnapshotDegradationReason.EXPIRED,
                failure.reason(),
                "an expired-but-validly-signed snapshot must surface reason EXPIRED");
    }

    @Test
    @DisplayName("deferredExecution fails closed for a validly-signed snapshot that is now past its effective "
            + "expiry, even though it was fresh when originally decoded")
    void expiredSnapshotFailsClosedOnDeferred() {
        Instant signedAt = Instant.parse("2026-07-01T11:00:00Z");
        IdentitySnapshotCodec signingCodec = fixedClockCodec(signedAt);
        IdentitySnapshot snapshot = signedSnapshot(
                signingCodec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of(), signedAt);

        IdentitySnapshotCodec verifyingCodec = fixedClockCodec(signedAt.plus(Duration.ofHours(2)));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(verifyingCodec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentityReconstructionException failure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.deferredExecution(executingService, snapshot, matching(snapshot)),
                "deferredExecution must fail closed for a snapshot that is now past its effective expiry, "
                        + "not merely on integrity failure");
        assertEquals(
                SnapshotDegradationReason.EXPIRED,
                failure.reason(),
                "an expired-but-validly-signed snapshot must surface reason EXPIRED");
    }

    @Test
    @DisplayName("setting the reconstructed marker never grants or removes standing by itself")
    void markerCannotEscalate() {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationEvidence realEvidence = new AuthenticationEvidence(
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Instant.now(),
                Optional.empty(),
                new CustomVerificationSource("test", Map.of()),
                Map.of());
        AuthenticationState liveAuth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(realEvidence), Optional.empty(), Optional.empty(), Map.of());
        AuthorityClaim realClaim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        AuthorizationClaims realClaims = new AuthorizationClaims(Set.of(realClaim), Map.of());

        AuthenticationState markedLiveAuth = new AuthenticationState(
                liveAuth.primaryMethod(),
                liveAuth.evidence(),
                liveAuth.assurance(),
                liveAuth.tokens(),
                Map.of("identity.reconstructed", "true", "identity.reconstructed.mode", "RESUME"));
        SecurityContext markedLiveCtx =
                SecurityContexts.assemble(identity, markedLiveAuth, realClaims, Optional.empty());

        assertTrue(
                IdentityReconstruction.isReconstructed(markedLiveCtx),
                "isReconstructed reports strictly on the marker attribute");
        assertFalse(
                markedLiveCtx.authentication().evidence().isEmpty(),
                "the marker must never strip real evidence — only the reconstruction path itself "
                        + "produces an evidence-less AuthenticationState");
        assertEquals(liveAuth.evidence(), markedLiveCtx.authentication().evidence());
        assertTrue(
                markedLiveCtx.authorization().claims().contains(realClaim),
                "the marker must never strip or add authorization standing");
    }

    @Test
    @DisplayName("resumeAsPrincipal's typed reconstruction marker carries no principal — Mode 2 resolves the "
            + "actor directly from the returned context's identity()")
    void resumeProducesTypedReconstructionMarker() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);

        IdentitySnapshot snapshot =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());

        SecurityContext resumed = reconstruction.resumeAsPrincipal(snapshot, matching(snapshot));

        assertTrue(resumed.reconstruction().isPresent(), "resumeAsPrincipal must produce a typed ReconstructionMarker");
        assertEquals(
                ReconstructedAuthorityMode.ATTRIBUTION_ONLY,
                resumed.reconstruction().get().mode(),
                "a default reconstruction's marker must carry the ATTRIBUTION_ONLY disposition");
        assertEquals(
                ACTOR,
                resumed.identity().actor(),
                "the resumed context's actor is the principal a Mode-2 authorizer resolves live");
    }

    @Test
    @DisplayName("deferredExecution's typed reconstruction marker carries no principal; the resumed context's "
            + "actor is the executing service and its subject is the subject-of-record (attribution only)")
    void deferredMarkerCarriesNoPrincipal() {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(new SnapshotHmac(KEYSET, ACTIVE_KEY_ID));
        DefaultIdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");

        IdentitySnapshot withSubject =
                signedSnapshot(codec, ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty(), List.of());
        SecurityContext deferredWithSubject =
                reconstruction.deferredExecution(executingService, withSubject, matching(withSubject));

        assertTrue(
                deferredWithSubject.reconstruction().isPresent(),
                "deferredExecution must produce a typed ReconstructionMarker");
        assertEquals(
                ReconstructedAuthorityMode.ATTRIBUTION_ONLY,
                deferredWithSubject.reconstruction().get().mode(),
                "a default reconstruction's marker must carry the ATTRIBUTION_ONLY disposition");
        assertEquals(
                executingService.actor(),
                deferredWithSubject.identity().actor(),
                "Mode 2 resolves the executing service's own authority — never the subject-of-record's "
                        + "(subject-authority evaluation, i.e. impersonation, is out of v1 scope per "
                        + "FR-ID-DG-006)");
        assertTrue(deferredWithSubject.identity().subject().isPresent());
        assertEquals(
                SUBJECT,
                deferredWithSubject.identity().subject().get(),
                "the subject-of-record remains on identity().subject() as attribution only");

        IdentitySnapshot withoutSubject =
                signedSnapshot(codec, ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        SecurityContext deferredWithoutSubject =
                reconstruction.deferredExecution(executingService, withoutSubject, matching(withoutSubject));

        assertTrue(deferredWithoutSubject.reconstruction().isPresent());
        assertEquals(executingService.actor(), deferredWithoutSubject.identity().actor());
        assertTrue(deferredWithoutSubject.identity().subject().isPresent());
        assertEquals(
                ACTOR,
                deferredWithoutSubject.identity().subject().get(),
                "with no snapshot subject, identity().subject() falls back to the snapshot actor");
    }

    @Test
    @DisplayName("a live-assembled context via SecurityContexts.assemble carries no reconstruction marker")
    void liveAssembledContextHasNoReconstructionMarker() {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState liveAuth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());

        SecurityContext live =
                SecurityContexts.assemble(identity, liveAuth, AuthorizationClaims.empty(), Optional.empty());

        assertTrue(
                live.reconstruction().isEmpty(),
                "a normal, live-authored SecurityContext must never carry a reconstruction marker");
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} by assembling an unsigned
     * envelope (fresh {@code issuedAt}/{@code expiresAt} so it passes decode-time freshness) and
     * round-tripping it through the codec's {@code encode}/{@code decode}.
     *
     * @param codec      the codec used to sign and verify the snapshot
     * @param actor      the acting principal to record on the snapshot content
     * @param subject    the optional subject-on-behalf-of to record
     * @param delegation the optional delegation summary to record
     * @param client     the optional OAuth client reference to record
     * @param claims     the authority claims to record
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            Optional<DelegationSummary> delegation,
            Optional<ClientRef> client,
            List<AuthorityClaim> claims) {
        return signedSnapshot(codec, actor, subject, delegation, client, claims, Instant.now());
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} signed with the given
     * {@code issuedAt} (a 1h signed window from it), rather than {@link Instant#now()} — so a caller can
     * pin a snapshot's temporal envelope to a deterministic instant, e.g. to later re-verify it against
     * a codec whose clock has moved past its effective expiry.
     *
     * @param codec      the codec used to sign and verify the snapshot
     * @param actor      the acting principal to record on the snapshot content
     * @param subject    the optional subject-on-behalf-of to record
     * @param delegation the optional delegation summary to record
     * @param client     the optional OAuth client reference to record
     * @param claims     the authority claims to record
     * @param issuedAt   the envelope's {@code issuedAt}; must not precede the fixed
     *                   {@code capturedAt}/{@code authenticatedAt} baked into this helper's content
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            Optional<DelegationSummary> delegation,
            Optional<ClientRef> client,
            List<AuthorityClaim> claims,
            Instant issuedAt) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                actor,
                subject,
                delegation,
                client,
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                claims,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                CARRIER,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "k1", "unsigned"));
        return codec.decode(codec.encode(unsigned));
    }

    /**
     * Builds an {@link IdentitySnapshotCodec} whose {@link SnapshotFreshnessPolicy} clock is fixed at
     * {@code now}, with no carrier/snapshot lifetime budgets and a 30s clock skew — used to sign/verify
     * a snapshot at a deterministic instant, and to model a later reconstruction attempt by fixing the
     * clock further ahead.
     *
     * @param now the fixed instant this codec's freshness policy treats as "current"
     * @return the fixed-clock codec, sharing {@link #KEYSET}/{@link #ACTIVE_KEY_ID}
     */
    private static IdentitySnapshotCodec fixedClockCodec(Instant now) {
        return new IdentitySnapshotCodec(
                new SnapshotHmac(KEYSET, ACTIVE_KEY_ID),
                new SnapshotFreshnessPolicy(
                        Optional.empty(), Optional.empty(), Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC)));
    }
}
