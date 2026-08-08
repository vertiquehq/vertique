// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.CapturedAuthorityReconstruction;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.events.CapturedAuthorityActivatedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for the Mode-3 activation seam ({@link CapturedAuthorityActivation}) and the
 * {@link CapturedAuthorityActivatedEvent} it emits — PRD identity-002 §14.3 Phase-2 Appendix,
 * §14.6 P2.S4b-ii (FR-ID-CA-007, FR-ID-CA-010).
 *
 * <p>Verifies the emit-and-await contract: {@link CapturedAuthorityActivation#activateResume}
 * invokes the underlying {@link CapturedAuthorityReconstruction} synchronously, builds a
 * {@link CapturedAuthorityActivatedEvent}, fans it out via {@link SecurityEventEmitter}, and its
 * returned {@link Future} resolves only <em>after</em> every registered observer's future has
 * settled — not merely after the observer method was invoked.
 *
 * <p>Verifies, in addition, the <strong>fact set</strong> the event carries, which is what makes an
 * audit consumer able to attribute a privileged captured-authority activation correctly:
 * <ul>
 *   <li>the reconstructed {@link SecurityIdentity} <strong>uncollapsed</strong> — on the deferred
 *       path the executing service actor, the captured subject-of-record, and the
 *       {@link DelegationContext#DEFERRED_EXECUTION_KIND} delegation are all distinguishable, and on
 *       the resume path both actor and subject come from the snapshot's own content;</li>
 *   <li>the typed {@link CapturedAuthorityActivatedEvent.Mode} component, which the compact
 *       constructor enforces as non-null — an invariant an unchecked {@code safeAttributes} marker
 *       could not provide;</li>
 *   <li>the reconstructed {@link AuthenticationState}, whose {@code primaryMethod} is the
 *       <em>original captured</em> method rather than a reconstruction marker, and which is
 *       credential-free — no evidence, no tokens — on both entry points;</li>
 *   <li>the activated {@link dev.vertique.security.authz.AuthorizationClaims}, so a record can state
 *       <em>which</em> privileges the activation granted and not merely that one occurred;</li>
 *   <li>the whole signed {@link SnapshotCarrierBinding}, {@code carrierId} included;</li>
 *   <li>a per-activation {@code activationId}, minted fresh so distinct activations of one carrier
 *       row remain distinguishable as audit source events;</li>
 *   <li>the emission envelope itself — a freshly stamped {@code occurredAt}, the unbound
 *       correlation sentinel, and the resolved context's own {@code origin}.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CapturedAuthorityActivatedEventTest {

    private static final String HMAC_SECRET = "super-secret-signing-key-material";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String ALLOWED_KIND = "outbox";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));

    private static final SnapshotCarrierBinding ALLOWED_CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget(ALLOWED_KIND, "orders", Optional.empty()));

    /** A grant-backed captured delegation — the resume path maps it onto a {@link DelegationContext}. */
    private static final DelegationSummary GRANTED_DELEGATION =
            new DelegationSummary("psd2-pis", Optional.of("consent-7"));

    /** The same grant-backed scheme captured <em>without</em> a grant identifier (F-A). */
    private static final DelegationSummary UNIDENTIFIED_DELEGATION =
            new DelegationSummary("psd2-pis", Optional.empty());

    private static final ClientRef CLIENT = new ClientRef("client-abc", "jwt-azp", Map.of("app", "mobile"));

    /**
     * A captured IdP assurance with a <strong>single</strong> {@code amr} value. The set is
     * deliberately one element: {@code IdentitySnapshotCodec.verifyIntegrity} re-canonicalizes the
     * typed model, and {@code AuthenticationAssurance.amr} is a {@code Set.copyOf} whose iteration
     * order is per-JVM salted, so a multi-value {@code amr} can re-serialize in a different order
     * than it was signed in and fail verification for reasons unrelated to this test.
     */
    private static final AuthenticationAssurance ASSURANCE = new AuthenticationAssurance(
            Optional.of("urn:mace:incommon:iap:silver"),
            Set.of("pwd"),
            Optional.of(Instant.parse("2026-07-01T10:15:29Z")),
            Optional.of(2));

    @Test
    @DisplayName("activateResume emits the CapturedAuthorityActivatedEvent and awaits full observer delivery "
            + "before resolving")
    void emittedAndAwaitedOnActivation(Vertx vertx, VertxTestContext testCtx) {
        IdentitySnapshotCodec codec = codec();

        AuthorityClaim roleClaim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        IdentitySnapshot snapshot =
                signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of(roleClaim));
        DurableCarrierDescriptor expectedCarrier = matching(snapshot);

        AtomicBoolean observerSettled = new AtomicBoolean(false);
        AtomicReference<CapturedAuthorityActivatedEvent> captured = new AtomicReference<>();

        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onCapturedAuthorityActivated(CapturedAuthorityActivatedEvent event) {
                captured.set(event);
                Promise<Void> promise = Promise.promise();
                // Genuinely asynchronous completion (not resolved on the calling thread) so the
                // test can prove the activation future does not resolve before this settles.
                vertx.setTimer(50, id -> {
                    observerSettled.set(true);
                    promise.complete();
                });
                return promise.future();
            }
        };

        CapturedAuthorityActivation activation = activation(codec, observer);

        activation
                .activateResume(snapshot, expectedCarrier)
                .onComplete(testCtx.succeeding(ctx -> testCtx.verify(() -> {
                    assertTrue(
                            observerSettled.get(),
                            "the activation future must not resolve before the observer's own future settled");

                    assertEquals(
                            ReconstructedAuthorityMode.CAPTURED,
                            ctx.reconstruction().orElseThrow().mode(),
                            "the resolved context must be the CAPTURED-mode reconstruction");
                    assertEquals(
                            Set.of(roleClaim),
                            ctx.authorization().claims(),
                            "the resolved context must carry the snapshot's captured claims as current authority");

                    CapturedAuthorityActivatedEvent event = captured.get();
                    assertNotNull(event, "the observer must have received exactly one activation event");
                    assertEquals(
                            Optional.of(SUBJECT),
                            event.identity().subject(),
                            "the subject-of-record must be carried on the event's identity, not collapsed away");
                    assertEquals(
                            ALLOWED_CARRIER.target(),
                            event.carrier().target(),
                            "the event's carrier binding must carry the snapshot's durable target");

                    testCtx.completeNow();
                })));
    }

    @Test
    @DisplayName("activateDeferred carries the executing service actor, the captured subject-of-record, and the "
            + "deferred-execution delegation as three distinguishable facts")
    void activateDeferred_carriesExecutingActorSubjectAndDelegation() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result =
                activation(codec, observer).activateDeferred(executingService, snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid deferred activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();
        assertEquals(
                executingService.actor(),
                event.identity().actor(),
                "the event's actor must be the executing service, not the captured subject");
        assertEquals(
                Optional.of(SUBJECT),
                event.identity().subject(),
                "the event's subject must be the captured subject-of-record");
        assertTrue(
                event.identity().delegation().isPresent(),
                "a deferred activation must carry the framework-mediated delegation on the event");
        assertEquals(
                DelegationContext.DEFERRED_EXECUTION_KIND,
                event.identity().delegation().orElseThrow().kind(),
                "the delegation kind must identify this as deferred execution under captured authority");
    }

    @Test
    @DisplayName(
            "activateResume carries the snapshot's own actor and subject uncollapsed, not one merged " + "principal")
    void activateResume_carriesSnapshotIdentityUncollapsed() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();
        assertEquals(
                snapshot.content().actor(),
                event.identity().actor(),
                "the event's actor must be the snapshot content's actor");
        assertEquals(
                snapshot.content().subject(),
                event.identity().subject(),
                "the event's subject must be the snapshot content's subject, carried alongside the actor");
        assertNotEquals(
                event.identity().actor(),
                event.identity().subject().orElseThrow(),
                "actor and subject must remain distinguishable — the event must not collapse them");
        assertTrue(
                event.identity().delegation().isEmpty(),
                "a snapshot carrying no captured delegation must yield no delegation on the event — the resume "
                        + "path must never fabricate one");
    }

    @Test
    @DisplayName("activateResume sets the typed activation mode to RESUME")
    void activateResume_setsModeResume() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        assertEquals(
                CapturedAuthorityActivatedEvent.Mode.RESUME,
                observer.single().mode(),
                "the resume entry point must stamp the event with Mode.RESUME");
    }

    @Test
    @DisplayName("activateDeferred sets the typed activation mode to DEFERRED")
    void activateDeferred_setsModeDeferred() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result =
                activation(codec, observer).activateDeferred(executingService, snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid deferred activation must resolve successfully");
        assertEquals(
                CapturedAuthorityActivatedEvent.Mode.DEFERRED,
                observer.single().mode(),
                "the deferred entry point must stamp the event with Mode.DEFERRED");
    }

    @Test
    @DisplayName("the compact constructor rejects a null activation mode — the invariant an unchecked "
            + "safeAttributes marker could not enforce")
    void constructorRejectsNullMode() {
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.custom("jwt"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        SecurityIdentity identity =
                new SecurityIdentity(ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty());
        Instant occurredAt = Instant.parse("2026-07-01T10:15:32Z");
        UUID activationId = UUID.randomUUID();

        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> new CapturedAuthorityActivatedEvent(
                        occurredAt,
                        CorrelationContext.unbound(),
                        Optional.empty(),
                        authentication,
                        identity,
                        AuthorizationClaims.empty(),
                        null,
                        activationId,
                        ALLOWED_CARRIER),
                "a null activation mode must be rejected at construction, never carried onto an audit record");
        assertEquals("mode", failure.getMessage(), "the rejection must name the offending component");
    }

    @Test
    @DisplayName("the compact constructor rejects null activated authority — an audit record must never claim an "
            + "activation occurred without stating which privileges it granted")
    void constructorRejectsNullAuthorization() {
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.custom("jwt"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        SecurityIdentity identity =
                new SecurityIdentity(ACTOR, Optional.of(SUBJECT), Optional.empty(), Optional.empty());
        Instant occurredAt = Instant.parse("2026-07-01T10:15:32Z");
        UUID activationId = UUID.randomUUID();

        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> new CapturedAuthorityActivatedEvent(
                        occurredAt,
                        CorrelationContext.unbound(),
                        Optional.empty(),
                        authentication,
                        identity,
                        null,
                        CapturedAuthorityActivatedEvent.Mode.RESUME,
                        activationId,
                        ALLOWED_CARRIER),
                "null activated authority must be rejected at construction, never carried onto an audit record");
        assertEquals("authorization", failure.getMessage(), "the rejection must name the offending component");
    }

    @Test
    @DisplayName("the activation event carries the whole signed carrier binding, carrierId included, not just its "
            + "durable target")
    void activation_carriesTheSignedCarrierBinding() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        assertEquals(
                snapshot.carrier(),
                observer.single().carrier(),
                "the event must carry the snapshot's signed carrier binding as one unit");
    }

    @Test
    @DisplayName("the activation event carries the original captured authentication method — the CAPTURED-* "
            + "reconstruction marker lives in safeAttributes, never on primaryMethod")
    void activation_carriesTheCapturedAuthenticationMethod() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        IdentitySnapshotContent content = snapshot.content();
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();
        assertEquals(
                DefaultAuthMethod.custom(content.authenticationMethodKind()),
                event.authentication().primaryMethod(),
                "the event's primary method must be the original captured method, not a reconstruction marker");
        assertEquals(
                "CAPTURED-RESUME",
                event.authentication().safeAttributes().get("identity.reconstructed.mode"),
                "the CAPTURED-* marker belongs to safeAttributes — asserting it here pins where it actually lives");
    }

    @Test
    @DisplayName("each activation mints its own activationId, so two activations of the same carrier row stay "
            + "distinguishable as audit source events")
    void activation_mintsAUniqueActivationIdPerActivation() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        DurableCarrierDescriptor expectedCarrier = matching(snapshot);
        CapturingObserver observer = new CapturingObserver();
        CapturedAuthorityActivation activation = activation(codec, observer);

        assertTrue(
                activation.activateResume(snapshot, expectedCarrier).succeeded(),
                "the first activation must resolve successfully");
        assertTrue(
                activation.activateResume(snapshot, expectedCarrier).succeeded(),
                "the second activation of the same snapshot and carrier must resolve successfully");

        assertEquals(2, observer.events().size(), "the observer must have received one event per activation");
        UUID first = observer.events().get(0).activationId();
        UUID second = observer.events().get(1).activationId();
        assertNotNull(first, "every activation must carry an activation id");
        assertNotNull(second, "every activation must carry an activation id");
        assertNotEquals(
                first,
                second,
                "two activations of the same carrier row must receive distinct activation ids, or a downstream "
                        + "deduplicator would erase the second");
    }

    @Test
    @DisplayName("the activation event carries the activated authority claims — the frozen captured claim set "
            + "reconstruction installs as the reconstructed context's current authority")
    void activation_carriesTheActivatedAuthorityClaims() {
        IdentitySnapshotCodec codec = codec();
        AuthorityClaim role = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        AuthorityClaim scope =
                new AuthorityClaim(AuthorityKind.SCOPE, "orders:write", "idp", "aud", "jwt-scope", Map.of());
        IdentitySnapshot snapshot =
                signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of(role, scope));
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();
        assertEquals(
                Set.of(role, scope),
                event.authorization().claims(),
                "the event must state which privileges the activation granted, not merely that one occurred");
        assertEquals(
                result.result().authorization().claims(),
                event.authorization().claims(),
                "the event's authorization must be the reconstructed context's own current authority");
    }

    @Test
    @DisplayName("the activation event carries the captured delegation, client, and assurance when the snapshot "
            + "carried them")
    void activation_carriesDelegationClientAndAssuranceWhenCaptured() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(
                codec,
                ALLOWED_CARRIER,
                ACTOR,
                Optional.of(SUBJECT),
                List.of(),
                Optional.of(GRANTED_DELEGATION),
                Optional.of(CLIENT),
                Optional.of(ASSURANCE));
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();

        DelegationContext delegation = event.identity()
                .delegation()
                .orElseThrow(() -> new AssertionError("the captured delegation must survive onto the event"));
        assertEquals(
                GRANTED_DELEGATION.kind(),
                delegation.kind(),
                "the resume path must map the captured delegation's scheme onto the event");
        assertEquals(
                GRANTED_DELEGATION.authorityId().orElseThrow(),
                delegation.authorityId(),
                "the resume path must map the captured grant id onto the event");
        assertEquals(
                Optional.of(CLIENT),
                event.identity().client(),
                "the captured OAuth client must survive onto the event's identity");
        assertEquals(
                Optional.of(ASSURANCE),
                event.authentication().assurance(),
                "the captured IdP assurance must survive onto the event's authentication state");
    }

    @Test
    @DisplayName("a resume whose captured delegation carried no grant id does not fabricate one that reads as the "
            + "deferred-execution marker")
    void activateResume_doesNotFabricateAnAuthorityIdWhenNoneWasCaptured() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(
                codec,
                ALLOWED_CARRIER,
                ACTOR,
                Optional.of(SUBJECT),
                List.of(),
                Optional.of(UNIDENTIFIED_DELEGATION),
                Optional.empty(),
                Optional.empty());
        CapturingObserver observer = new CapturingObserver();

        Future<SecurityContext> result = activation(codec, observer).activateResume(snapshot, matching(snapshot));

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        DelegationContext delegation = observer.single()
                .identity()
                .delegation()
                .orElseThrow(() -> new AssertionError("the captured delegation must survive onto the event"));
        assertEquals(
                UNIDENTIFIED_DELEGATION.kind(),
                delegation.kind(),
                "the captured delegation scheme must be preserved verbatim");
        assertEquals(
                "no-captured-authority-id",
                delegation.authorityId(),
                "a resume with no captured grant id must state the absence with its own literal, never substitute "
                        + "the deferred-execution one — an audit consumer could not tell that from a real grant id "
                        + "with that value");
    }

    @Test
    @DisplayName("the activation event carries a credential-free authentication state on both entry points")
    void activation_carriesACredentialFreeAuthenticationState() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(
                codec,
                ALLOWED_CARRIER,
                ACTOR,
                Optional.of(SUBJECT),
                List.of(),
                Optional.of(GRANTED_DELEGATION),
                Optional.of(CLIENT),
                Optional.of(ASSURANCE));
        DurableCarrierDescriptor expectedCarrier = matching(snapshot);
        CapturingObserver observer = new CapturingObserver();
        CapturedAuthorityActivation activation = activation(codec, observer);

        assertTrue(
                activation.activateResume(snapshot, expectedCarrier).succeeded(),
                "a valid resume activation must resolve successfully");
        assertTrue(
                activation
                        .activateDeferred(
                                SystemIdentities.scheduledJob("nightly-reconciliation"), snapshot, expectedCarrier)
                        .succeeded(),
                "a valid deferred activation must resolve successfully");

        assertEquals(2, observer.events().size(), "the observer must have received one event per activation");
        for (CapturedAuthorityActivatedEvent event : observer.events()) {
            AuthenticationState authentication = event.authentication();
            assertTrue(
                    authentication.evidence().isEmpty(),
                    "observers are arbitrary application code — an activation event must never carry credential "
                            + "evidence");
            assertTrue(
                    authentication.tokens().isEmpty(),
                    "observers are arbitrary application code — an activation event must never carry token material");
        }
    }

    @Test
    @DisplayName("the activation event pins its emission envelope: a fresh occurredAt, the unbound correlation, the "
            + "resolved context's own origin, and the reconstruction's own context instance")
    void activation_pinsTheEmissionEnvelope() {
        IdentitySnapshotCodec codec = codec();
        IdentitySnapshot snapshot = signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of());
        CapturingObserver observer = new CapturingObserver();
        AtomicReference<SecurityContext> reconstructed = new AtomicReference<>();
        CapturedAuthorityActivation activation = new CapturedAuthorityActivation(
                recording(new DefaultCapturedAuthorityReconstruction(codec, Set.of(ALLOWED_KIND)), reconstructed),
                new SecurityEventEmitter(Set.of(observer)));

        Instant before = Instant.now();
        Future<SecurityContext> result = activation.activateResume(snapshot, matching(snapshot));
        Instant after = Instant.now();

        assertTrue(result.succeeded(), "a valid resume activation must resolve successfully");
        CapturedAuthorityActivatedEvent event = observer.single();

        assertFalse(
                event.occurredAt().isBefore(before),
                "occurredAt must be stamped during the activation, not defaulted to an epoch or a captured instant");
        assertFalse(event.occurredAt().isAfter(after), "occurredAt must be stamped during the activation");
        assertSame(
                CorrelationContext.unbound(),
                event.correlation(),
                "activation is not necessarily tied to a live inbound request, so the correlation is the unbound "
                        + "sentinel — the fact the audit projector keys its empty-correlation mapping on");
        assertEquals(
                result.result().origin(),
                event.origin(),
                "the event's origin must mirror the resolved context's own origin, whatever it is");
        assertSame(
                reconstructed.get(),
                result.result(),
                "the seam must resolve to the reconstruction's own context instance, never a rebuilt copy");
    }

    /**
     * The shared HMAC-signing codec used by the fact-set tests, keyed on {@link #ACTIVE_KEY_ID}.
     *
     * @return a codec signing and verifying with {@link #HMAC_SECRET}
     */
    private static IdentitySnapshotCodec codec() {
        return new IdentitySnapshotCodec(new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID));
    }

    /**
     * Builds an activation seam over a {@link DefaultCapturedAuthorityReconstruction} allowlisting
     * {@link #ALLOWED_KIND}, fanning out to exactly one observer.
     *
     * @param codec    the codec the reconstruction verifies snapshots with
     * @param observer the single observer the emitter fans out to
     * @return the activation seam under test
     */
    private static CapturedAuthorityActivation activation(IdentitySnapshotCodec codec, SecurityEventObserver observer) {
        return new CapturedAuthorityActivation(
                new DefaultCapturedAuthorityReconstruction(codec, Set.of(ALLOWED_KIND)),
                new SecurityEventEmitter(Set.of(observer)));
    }

    /**
     * Wraps a reconstruction so the {@link SecurityContext} instance it mints is observable to the
     * test, which is what makes the seam's "resolves to the reconstruction's own context" contract
     * assertable by reference identity rather than by equality.
     *
     * @param delegate     the reconstruction to delegate to
     * @param reconstructed the holder receiving every context {@code delegate} mints
     * @return a recording {@link CapturedAuthorityReconstruction}
     */
    private static CapturedAuthorityReconstruction recording(
            CapturedAuthorityReconstruction delegate, AtomicReference<SecurityContext> reconstructed) {
        return new CapturedAuthorityReconstruction() {
            @Override
            public SecurityContext resumeWithCapturedAuthority(
                    IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
                SecurityContext ctx = delegate.resumeWithCapturedAuthority(snapshot, expectedCarrier);
                reconstructed.set(ctx);
                return ctx;
            }

            @Override
            public SecurityContext deferredExecutionWithCapturedAuthority(
                    SecurityIdentity executingServiceIdentity,
                    IdentitySnapshot snapshot,
                    DurableCarrierDescriptor expectedCarrier) {
                SecurityContext ctx = delegate.deferredExecutionWithCapturedAuthority(
                        executingServiceIdentity, snapshot, expectedCarrier);
                reconstructed.set(ctx);
                return ctx;
            }
        };
    }

    /**
     * The trusted receive-side expected carrier that matches {@code snapshot}'s signed carrier.
     * Mirrors {@code CapturedAuthorityReconstructionTest.matching}.
     *
     * @param snapshot the snapshot whose carrier to mirror
     * @return the matching {@link DurableCarrierDescriptor}
     */
    private static DurableCarrierDescriptor matching(IdentitySnapshot snapshot) {
        return new DurableCarrierDescriptor(
                snapshot.carrier().carrierId(), snapshot.carrier().target());
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} carrying no
     * delegation, client, or assurance — the shape the identity-structure tests need.
     *
     * @param codec   the codec used to sign and verify the snapshot
     * @param carrier the carrier binding to sign the snapshot for
     * @param actor   the acting principal to record on the snapshot content
     * @param subject the optional subject-on-behalf-of to record
     * @param claims  the authority claims to record
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            SnapshotCarrierBinding carrier,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            List<AuthorityClaim> claims) {
        return signedSnapshot(
                codec, carrier, actor, subject, claims, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} bound to
     * {@code carrier}, round-tripped through the codec's {@code encode}/{@code decode} so it
     * carries a real, verifiable tag. Mirrors {@code CapturedAuthorityReconstructionTest.signedSnapshot}.
     *
     * <p>{@code delegation}, {@code client}, and {@code assurance} are parameters rather than
     * hard-coded empties deliberately: with all three pinned empty, the resume path's delegation
     * mapping, the identity's client component, and the authentication state's assurance were
     * unexercised by every test in this class, so a defect in any of them was invisible.
     *
     * @param codec      the codec used to sign and verify the snapshot
     * @param carrier    the carrier binding to sign the snapshot for
     * @param actor      the acting principal to record on the snapshot content
     * @param subject    the optional subject-on-behalf-of to record
     * @param claims     the authority claims to record
     * @param delegation the optional captured delegation summary to record
     * @param client     the optional OAuth client reference to record
     * @param assurance  the optional IdP-reported authentication assurance to record
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            SnapshotCarrierBinding carrier,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            List<AuthorityClaim> claims,
            Optional<DelegationSummary> delegation,
            Optional<ClientRef> client,
            Optional<AuthenticationAssurance> assurance) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                actor,
                subject,
                delegation,
                client,
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                assurance,
                claims,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        Instant issuedAt = Instant.now();
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                carrier,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", ACTIVE_KEY_ID, "unsigned"));
        return codec.decode(codec.encode(unsigned));
    }

    /**
     * Observer test double that records every {@link CapturedAuthorityActivatedEvent} it receives
     * and settles synchronously, so an activation resolves on the calling thread.
     */
    private static final class CapturingObserver implements SecurityEventObserver {

        private final List<CapturedAuthorityActivatedEvent> events = new ArrayList<>();

        @Override
        public Future<Void> onCapturedAuthorityActivated(CapturedAuthorityActivatedEvent event) {
            events.add(event);
            return Future.succeededFuture();
        }

        /**
         * Returns every activation event received, in arrival order.
         *
         * @return the recorded events
         */
        List<CapturedAuthorityActivatedEvent> events() {
            return events;
        }

        /**
         * Returns the single activation event received, failing the test when the count is not one.
         *
         * @return the only recorded event
         */
        CapturedAuthorityActivatedEvent single() {
            assertEquals(1, events.size(), "the observer must have received exactly one activation event");
            return events.get(0);
        }
    }
}
