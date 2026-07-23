// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.IdentitySnapshotDegradationEvent;
import dev.vertique.security.runtime.DefaultIdentityReconstruction;
import dev.vertique.security.runtime.DefaultIdentitySnapshotFactory;
import dev.vertique.security.runtime.IdentitySnapshotCodec;
import dev.vertique.security.runtime.IdentitySnapshotConfig;
import dev.vertique.security.runtime.IdentitySnapshotContext;
import dev.vertique.security.runtime.IdentitySnapshotDegradationPolicy;
import dev.vertique.security.runtime.IdentitySnapshotDurableDecoder;
import dev.vertique.security.runtime.IdentitySnapshotDurableEncoder;
import dev.vertique.security.runtime.IdentitySnapshotReconstructionInitializer;
import dev.vertique.security.runtime.SnapshotHmac;
import dev.vertique.security.runtime.SnapshotHmacConfig;
import dev.vertique.security.runtime.SnapshotHmacKeyConfig;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Proves the full identity-snapshot deferred-execution path end to end — capture, durable
 * carriage, receive-side reconstruction, and (on degradation) the async {@link SnapshotDegradationGate}
 * — without needing postgres or a real event-bus dispatch (PRD-ID-002 §14.3 "Durable carriage",
 * §14.6 slice P1.S5c).
 *
 * <p>The harness uses the real {@link DefaultContextHolder} (backed by a duplicated Vert.x context,
 * exactly as production dispatch does), the real {@link IdentitySnapshotDurableEncoder}/
 * {@link IdentitySnapshotDurableDecoder} pair, the real {@link IdentitySnapshotReconstructionInitializer},
 * and the real {@link SnapshotDegradationGate} — only the event bus / delayed-job poller / postgres
 * layers are out of scope for this class.
 *
 * <ul>
 *   <li>{@link #captureCarryReconstructRoundTrip} — the success path: a live user
 *       {@link SecurityContext} is captured into an {@link dev.vertique.security.IdentitySnapshot},
 *       encoded to {@link DurableMetadata}, decoded on a fresh (duplicated) context, and the
 *       initializer reconstructs a {@link SecurityContext} whose actor is the service identity and
 *       whose subject is the original captured user (subject-of-record).</li>
 *   <li>{@link #forgedSnapshotDegradesAndGateFails} / {@link #forgedSnapshotDegradesAndGateContinues} —
 *       the reconstruction-re-verification path (case 3b) with <em>no</em> {@link DeferredExecutionOrigin}
 *       bound (an app-forged snapshot bound out of band, exactly the F5/A9 attack shape): a
 *       verified-shaped snapshot is hand-bound onto the holder but its integrity tag fails the
 *       initializer's re-verification, so the initializer binds only a
 *       {@link SnapshotDegradationMarker} — no {@link SecurityContext} — per the origin-or-verified
 *       mint evidence rule (A9), and that marker drives {@link SnapshotDegradationGate} end to end
 *       under both {@code FAIL} and {@code CONTINUE_WITHOUT_IDENTITY} policies.</li>
 *   <li>{@link #tamperedDurableBytesEmitDegradationAndFail} — the durable-decode path (case 3a):
 *       corrupted durable snapshot bytes are decoded by the <em>real</em>
 *       {@link IdentitySnapshotDurableDecoder}, which binds a present-but-unverifiable
 *       {@link IdentitySnapshotContext}; the real initializer degrades it to a marker and the real
 *       gate {@code FAIL}s the dispatch. This is the end-to-end regression proof that a tampered
 *       durable snapshot can no longer silently bypass the degradation gate.</li>
 *   <li>{@link #ordinaryContextEmptyDispatchFailsClosed} — the W2 bounded-mint security boundary,
 *       fail-closed side (case 5): a context-empty dispatch with no snapshot and no
 *       {@link DeferredExecutionOrigin} runs through the <em>real</em>
 *       {@link IdentitySnapshotReconstructionInitializer} (which binds nothing) and then the
 *       <em>real</em> {@link ServiceAuthorizationInterceptor} on a {@code @RequiresAction}-gated
 *       operation — the PEP denies the dispatch closed with
 *       {@link AuthzReasonCodes#AUTHENTICATION_REQUIRED} and never authorizes anonymously. This is the
 *       permanent falsification test that case 5 actually <em>denies</em>, not merely "binds
 *       nothing".</li>
 *   <li>{@link #deferredOriginDispatchRunsAsSystem} — the W2 bounded-mint security boundary,
 *       minted side (case 4): the same setup but with a {@link DeferredExecutionOrigin} bound as the
 *       receive-path context decoder would — the initializer mints a bounded SYSTEM context and the
 *       real PEP permits the gated dispatch, authorizing against the minted SYSTEM actor whose
 *       {@code system.reason} is the origin reference.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IdentitySnapshotRoundTripTest {

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef USER =
            new PrincipalRef(PrincipalType.USER, "user-77", Map.of("realm", "acme-realm"));

    // Capture projects free-form attributes out (W2 credential-free enforcement); only type/id (+
    // allowlisted system.reason) survive the durable round trip.
    private static final PrincipalRef PROJECTED_USER = new PrincipalRef(PrincipalType.USER, "user-77", Map.of());

    /** Action value gating the operation exercised by the W2 bounded-mint boundary tests. */
    private static final String GATED_ACTION_VALUE = "svc.resource.exec";

    private static SnapshotHmac hmac() {
        return new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
    }

    /**
     * Builds a minimal valid {@link IdentitySnapshotConfig} with no {@code carriageRequirements}
     * entries — every target-kind resolves to {@code OPTIONAL}, matching this test class's
     * pre-P2.S0-commit-5b behavior (none of these scenarios exercise a REQUIRED target-kind).
     *
     * @return the config fixture
     */
    private static IdentitySnapshotConfig identitySnapshotConfig() {
        return new IdentitySnapshotConfig(
                true,
                IdentitySnapshotDegradationPolicy.FAIL,
                new SnapshotHmacConfig(
                        new SnapshotHmacKeyConfig("key-1", "super-secret-signing-key-material"), List.of()),
                null,
                null,
                30_000L,
                Map.of());
    }

    /**
     * Builds a live {@link SecurityContext} whose identity dimension carries {@code USER} as the
     * subject-of-record via a delegation ({@link SecurityIdentity#subject()} is only ever populated
     * for a delegated request — a directly-authenticated user has no separate subject; see
     * {@link SecurityIdentity} javadoc). This is the realistic shape for a deferred execution
     * scheduled on behalf of a user (e.g. a delayed job enqueued from an authenticated REST request).
     *
     * @return the live delegated {@link SecurityContext}
     */
    private static SecurityContext liveUserContext() {
        SecurityIdentity identity = new SecurityIdentity(ACTOR, Optional.of(USER), Optional.empty(), Optional.empty());
        return SecurityContexts.assemble(
                identity,
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), java.util.List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    private static ServiceDispatchContext dispatchContext() {
        return new ServiceDispatchContext(
                "services/svc/exec",
                "svc.exec",
                "",
                "svc",
                "exec",
                DispatchEnvelope.of("payload"),
                false,
                null,
                null,
                Map.of());
    }

    @Test
    @DisplayName("capture -> durable carriage -> receive-side reconstruction round-trips the subject-of-record")
    void captureCarryReconstructRoundTrip(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac());
            IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
            IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

            DefaultIdentitySnapshotFactory snapshotFactory =
                    new DefaultIdentitySnapshotFactory(new DefaultContextHolder());

            // --- capture: live user SecurityContext -> captured content ---
            SecurityContext live = liveUserContext();
            dev.vertique.security.IdentitySnapshotContent captured = snapshotFactory.capture(live);
            assertEquals(
                    PROJECTED_USER,
                    captured.subject().orElseThrow(),
                    "capture must preserve the subject-of-record with free-form attributes projected out");

            // --- durable carriage: encode to DurableMetadata, decode back on a fresh holder ---
            // F5/F3a row binding: mirrors DelayedJobService.toExecution / DelayedJobPoller.dispatch,
            // which thread a real per-row DurableCarrierDescriptor rather than the carrier-less
            // overloads. The delayed-job boundary is wired with a real carrier, so its snapshot is NOT
            // sentinel-signed and must still reconstruct under the F3a fail-closed sentinel backstop.
            DurableCarrierDescriptor rowCarrier = new DurableCarrierDescriptor(
                    "execution-1", new DurableTarget("delayed-job", "job.delayed.test-handler", Optional.empty()));
            IdentitySnapshotContext toEncode = IdentitySnapshotContext.of(captured);
            DurableMetadata metadata =
                    encoder.encode(toEncode, new DurableEncodeContext("delayed-job", Optional.of(rowCarrier)));

            ContextDecodeResult<IdentitySnapshotContext> decodeResult =
                    decoder.decode(metadata, new DurableDecodeContext("delayed-job", Optional.of(rowCarrier)));
            assertTrue(decodeResult.value().isPresent(), "an untampered captured snapshot must decode successfully");

            ContextHolder holder = new DefaultContextHolder();
            try (ContextHolder.Scope snapshotScope = holder.bind(
                    IdentitySnapshotContext.class, decodeResult.value().orElseThrow())) {

                // --- receive-side reconstruction ---
                dev.vertique.security.IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
                dev.vertique.security.runtime.ServiceIdentityResolver resolver =
                        origin -> dev.vertique.security.SystemIdentities.scheduledJob(origin.reference());
                IdentitySnapshotReconstructionInitializer initializer = new IdentitySnapshotReconstructionInitializer(
                        holder, reconstruction, Optional.of(resolver), identitySnapshotConfig());

                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                    SecurityContext reconstructed =
                            holder.current(SecurityContext.class).orElseThrow();

                    assertTrue(
                            reconstructed.identity().actor().type() == PrincipalType.SYSTEM,
                            "reconstructed actor must be the service (system) identity, not the original user");
                    assertEquals(
                            "system:scheduledJob",
                            reconstructed.identity().actor().id());
                    assertTrue(reconstructed.identity().subject().isPresent(), "subject-of-record must be present");
                    assertEquals(
                            PROJECTED_USER,
                            reconstructed.identity().subject().get(),
                            "reconstructed subject must equal the originally captured user with free-form"
                                    + " attributes projected out");
                    assertTrue(
                            holder.current(SnapshotDegradationMarker.class).isEmpty(),
                            "a full round trip through carriage must not degrade");
                }
            }
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("unverifiable snapshot -> degradation marker -> SnapshotDegradationGate FAILs the dispatch")
    void forgedSnapshotDegradesAndGateFails(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();
            SnapshotDegradationMarker marker = bindTamperedSnapshotAndReconstruct(holder);

            assertEquals(SnapshotDegradationReason.BAD_HMAC.name(), marker.reasonCode());
            assertTrue(
                    holder.current(SecurityContext.class).isEmpty(),
                    "an unverifiable snapshot with no DeferredExecutionOrigin must not mint a fallback "
                            + "SecurityContext (origin-or-verified mint evidence rule, A9)");

            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate =
                    new SnapshotDegradationGate(holder, emitter, Optional.of(IdentitySnapshotDegradationPolicy.FAIL));

            Future<ServiceDispatchContext> result = gate.beforeDispatch(dispatchContext());

            assertTrue(result.failed(), "FAIL policy must abort a dispatch carrying a degraded snapshot");
            assertInstanceOf(SnapshotDegradationForbiddenException.class, result.cause());

            ArgumentCaptor<IdentitySnapshotDegradationEvent> captor =
                    ArgumentCaptor.forClass(IdentitySnapshotDegradationEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertEquals(
                    SnapshotDegradationReason.BAD_HMAC.name(),
                    captor.getValue().reasonCode(),
                    "the emitted event must carry the initializer-bound marker's reasonCode");

            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("unverifiable snapshot -> degradation marker -> SnapshotDegradationGate CONTINUE_WITHOUT_IDENTITYs")
    void forgedSnapshotDegradesAndGateContinues(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();
            bindTamperedSnapshotAndReconstruct(holder);

            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate = new SnapshotDegradationGate(
                    holder, emitter, Optional.of(IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY));

            ServiceDispatchContext dispatch = dispatchContext();
            Future<ServiceDispatchContext> result = gate.beforeDispatch(dispatch);

            assertTrue(result.succeeded(), "CONTINUE_WITHOUT_IDENTITY must let the dispatch proceed subject-less");
            assertTrue(
                    holder.current(SecurityContext.class).isEmpty(),
                    "with no DeferredExecutionOrigin proving deferred execution, the continued dispatch must "
                            + "carry no SecurityContext at all (origin-or-verified mint evidence rule, A9) — "
                            + "not merely a subject-less one");

            verify(emitter, times(1)).emit(any(IdentitySnapshotDegradationEvent.class));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("tampered durable bytes -> real decoder binds an unverifiable context -> gate FAILs the dispatch")
    void tamperedDurableBytesEmitDegradationAndFail(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac());
            IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
            IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

            // Produce a valid signed snapshot and carry it as durable metadata exactly as the
            // producer side does, then corrupt the encoded snapshot bytes so the receiver can no
            // longer verify them — the F2 threat: a tampered/unverifiable durable snapshot.
            dev.vertique.security.IdentitySnapshotContent captured =
                    new DefaultIdentitySnapshotFactory(new DefaultContextHolder()).capture(liveUserContext());
            DurableMetadata metadata =
                    encoder.encode(IdentitySnapshotContext.of(captured), new DurableEncodeContext("delayed-job"));
            DurableMetadata tamperedMetadata = tamperEncodedSnapshot(metadata);

            // Real decode of the tampered bytes: a present-but-unverifiable snapshot must bind an
            // unverifiable context, not silently drop to the no-snapshot (case 4) path.
            ContextDecodeResult<IdentitySnapshotContext> decodeResult =
                    decoder.decode(tamperedMetadata, new DurableDecodeContext("delayed-job"));

            ContextHolder holder = new DefaultContextHolder();
            decodeResult.value().ifPresent(bound -> holder.bind(IdentitySnapshotContext.class, bound));

            // Real receive-side initializer: must fall to the degradation (case 3) path and bind a marker.
            dev.vertique.security.IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
            dev.vertique.security.runtime.ServiceIdentityResolver resolver =
                    origin -> dev.vertique.security.SystemIdentities.scheduledJob(origin.reference());
            new IdentitySnapshotReconstructionInitializer(
                            holder, reconstruction, Optional.of(resolver), identitySnapshotConfig())
                    .initialize(new InboundContextInitializationContext("delayed-job"));

            // Real degradation gate under FAIL: the dispatch must fail after the event is awaited.
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());
            SnapshotDegradationGate gate =
                    new SnapshotDegradationGate(holder, emitter, Optional.of(IdentitySnapshotDegradationPolicy.FAIL));

            gate.beforeDispatch(dispatchContext())
                    .onComplete(dispatch -> ctx.verify(() -> {
                        assertTrue(
                                decodeResult.value().isPresent(),
                                "present-but-unverifiable durable bytes must bind an unverifiable context, not drop silently");
                        SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                                .orElseThrow(
                                        () -> new AssertionError(
                                                "tampered durable bytes must bind a degradation marker via the real decode->initialize chain"));
                        assertTrue(
                                marker.reasonCode().equals(SnapshotDegradationReason.BAD_HMAC.name())
                                        || marker.reasonCode().equals(SnapshotDegradationReason.DECODE_FAILED.name()),
                                "an unverifiable durable snapshot must bind a BAD_HMAC or DECODE_FAILED marker, was "
                                        + marker.reasonCode());
                        assertTrue(
                                dispatch.failed(), "FAIL policy must abort a dispatch carrying tampered durable bytes");
                        assertInstanceOf(SnapshotDegradationForbiddenException.class, dispatch.cause());
                        verify(emitter, times(1)).emit(any(IdentitySnapshotDegradationEvent.class));
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("context-empty dispatch (no snapshot, no origin) fails closed at the @RequiresAction PEP")
    void ordinaryContextEmptyDispatchFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();

            // Receive-side reconstruction with NO live SecurityContext, NO IdentitySnapshotContext,
            // and NO DeferredExecutionOrigin bound — the initializer's case 5 (fail closed) binds
            // nothing at all.
            try (ContextHolder.Scope scope = reconstructionInitializer(holder)
                    .initialize(new InboundContextInitializationContext("services/svc/exec"))) {
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "case 5 must bind no SecurityContext for a context-empty dispatch with no "
                                + "deferred-execution provenance");

                // Drive the real @RequiresAction PEP against the same holder: an unbound identity must
                // fail the gated dispatch closed with AUTHENTICATION_REQUIRED, never authorizing an
                // anonymous stand-in. This is the security OUTCOME the fail-closed case 5 must produce.
                Authorizer authorizer = mock(Authorizer.class);
                SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
                when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
                ServiceAuthorizationInterceptor pep = authorizationPep(authorizer, emitter, holder);

                Future<ServiceDispatchContext> result =
                        pep.beforeDispatch(gatedDispatchContext(List.of(requiresActionOn("gatedOperationHolder"))));

                assertTrue(result.failed(), "an unbound SecurityContext must fail the gated dispatch closed");
                verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
                ArgumentCaptor<AuthorizationDecisionEvent> captor =
                        ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
                verify(emitter, times(1)).emit(captor.capture());
                assertFalse(captor.getValue().decision().permitted(), "the emitted decision must be a deny");
                assertEquals(
                        AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                        captor.getValue().decision().reasonCode(),
                        "a context-empty gated dispatch must deny with AUTHENTICATION_REQUIRED");
            }
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("snapshot-less dispatch carrying a DeferredExecutionOrigin runs as a minted SYSTEM context")
    void deferredOriginDispatchRunsAsSystem(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();

            // The receive path binds the carried DeferredExecutionOrigin onto the request-scoped
            // holder (as the FQCN-keyed context-map decoder does, keyed by
            // DeferredExecutionOrigin.class.getName()) before the initializers run.
            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-77"))) {
                try (ContextHolder.Scope scope = reconstructionInitializer(holder)
                        .initialize(new InboundContextInitializationContext("delayed-job"))) {

                    // Case 4: proven deferred execution mints a bounded SYSTEM context.
                    SecurityContext minted =
                            holder.current(SecurityContext.class).orElseThrow();
                    assertEquals(
                            PrincipalType.SYSTEM, minted.identity().actor().type(), "case 4 must mint a SYSTEM actor");
                    assertEquals(
                            "order-77",
                            minted.identity().actor().attributes().get("system.reason"),
                            "the minted SYSTEM context's reason must equal the origin reference");

                    // Drive the real @RequiresAction PEP against the same holder: the gated operation
                    // is permitted (contrast with the fail-closed test) and authorized against the
                    // minted SYSTEM context.
                    Authorizer authorizer = mock(Authorizer.class);
                    when(authorizer.authorize(any(AuthorizationRequest.class)))
                            .thenReturn(
                                    Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
                    SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
                    when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
                    ServiceAuthorizationInterceptor pep = authorizationPep(authorizer, emitter, holder);

                    Future<ServiceDispatchContext> result =
                            pep.beforeDispatch(gatedDispatchContext(List.of(requiresActionOn("gatedOperationHolder"))));

                    assertTrue(result.succeeded(), "a minted SYSTEM context must permit the gated dispatch");

                    // The PEP must have authorized against the minted SYSTEM actor, not an anonymous or
                    // user identity — capture the AuthorizationRequest the Authorizer was invoked with.
                    ArgumentCaptor<AuthorizationRequest> requestCaptor =
                            ArgumentCaptor.forClass(AuthorizationRequest.class);
                    verify(authorizer).authorize(requestCaptor.capture());
                    SecurityContext authorized = requestCaptor.getValue().securityContext();
                    assertEquals(
                            PrincipalType.SYSTEM,
                            authorized.identity().actor().type(),
                            "the PEP must authorize against the minted SYSTEM actor");
                    assertEquals(
                            "order-77",
                            authorized.identity().actor().attributes().get("system.reason"),
                            "the authorized SYSTEM context must carry the origin reference as its system reason");

                    ArgumentCaptor<AuthorizationDecisionEvent> captor =
                            ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
                    verify(emitter, times(1)).emit(captor.capture());
                    assertTrue(captor.getValue().decision().permitted(), "the emitted decision must be a permit");
                }
            }
            ctx.completeNow();
        }));
    }

    // --- W2 bounded-mint boundary helpers ---

    /**
     * Builds the real receive-side {@link IdentitySnapshotReconstructionInitializer} wired against
     * {@code holder}, using the built-in {@code scheduledJob} service-identity resolver — the same
     * wiring the shipped receive path installs.
     *
     * @param holder the request-scoped context holder to read/bind against
     * @return the real reconstruction initializer
     */
    private static IdentitySnapshotReconstructionInitializer reconstructionInitializer(ContextHolder holder) {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac());
        dev.vertique.security.IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        dev.vertique.security.runtime.ServiceIdentityResolver resolver =
                origin -> dev.vertique.security.SystemIdentities.scheduledJob(origin.reference());
        return new IdentitySnapshotReconstructionInitializer(
                holder, reconstruction, Optional.of(resolver), identitySnapshotConfig());
    }

    /**
     * Builds the real {@link ServiceAuthorizationInterceptor} PEP reading the propagated
     * {@link SecurityContext} back from {@code holder}. No {@link ServiceMethodMeta} are registered, so
     * the construction-time action-gate scan is a no-op; the runtime gate is exercised directly.
     *
     * @param authorizer the core {@link Authorizer} the gate delegates to
     * @param emitter    the security event emitter
     * @param holder     the request-scoped holder the gate reads the propagated identity from
     * @return the real services {@code @RequiresAction} PEP
     */
    private static ServiceAuthorizationInterceptor authorizationPep(
            Authorizer authorizer, SecurityEventEmitter emitter, ContextHolder holder) {
        return new ServiceAuthorizationInterceptor(
                Optional.of(authorizer), Optional.of(mock(ActionRegistry.class)), emitter, holder, Set.of());
    }

    /**
     * Builds a {@code @RequiresAction}-gated {@link ServiceDispatchContext} carrying the given
     * method-level annotations.
     *
     * @param methodAnnotations the boot-time-resolved method annotations (carries the
     *                          {@link RequiresAction} gate)
     * @return a gated dispatch context
     */
    private static ServiceDispatchContext gatedDispatchContext(List<Annotation> methodAnnotations) {
        return new ServiceDispatchContext(
                "services/svc/exec",
                "svc.exec",
                "",
                "svc",
                "exec",
                DispatchEnvelope.of("payload"),
                false,
                methodAnnotations,
                List.of(),
                Map.of());
    }

    /** Operation holder whose {@link RequiresAction} is harvested reflectively to gate the dispatch. */
    @RequiresAction(GATED_ACTION_VALUE)
    private void gatedOperationHolder() {}

    /**
     * Returns the {@link RequiresAction} declared on the named private method of this test class.
     *
     * @param methodName the declaring method name
     * @return the harvested {@link RequiresAction}
     */
    private static RequiresAction requiresActionOn(String methodName) {
        try {
            Method m = IdentitySnapshotRoundTripTest.class.getDeclaredMethod(methodName);
            RequiresAction ra = m.getAnnotation(RequiresAction.class);
            if (ra == null) {
                throw new IllegalStateException("no @RequiresAction on " + methodName);
            }
            return ra;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Corrupts the base64-encoded codec bytes carried in the {@code identity-snapshot} namespace
     * body so the codec can no longer verify them on decode, while leaving the surrounding document
     * well-formed (still base64, still a valid namespace body).
     *
     * @param metadata the validly encoded metadata to tamper with
     * @return a new {@link DurableMetadata} carrying the corrupted snapshot bytes
     */
    private static DurableMetadata tamperEncodedSnapshot(DurableMetadata metadata) {
        JsonObject body = metadata.body("identity-snapshot").orElseThrow();
        byte[] raw = java.util.Base64.getDecoder().decode(body.getString("snapshot"));
        raw[raw.length - 1] ^= 0x01;
        String tamperedBase64 = java.util.Base64.getEncoder().encodeToString(raw);
        return DurableMetadata.of("identity-snapshot", body.copy().put("snapshot", tamperedBase64));
    }

    /**
     * Binds a tampered (HMAC-invalid) {@link IdentitySnapshotContext} directly on {@code holder}
     * (simulating a decode that already failed verification once and was resurfaced, or an
     * app-forged snapshot bound out of band) with <em>no</em> {@link DeferredExecutionOrigin} bound
     * — the F5/A9 attack shape — and runs the real {@link IdentitySnapshotReconstructionInitializer}
     * against it, returning the {@link SnapshotDegradationMarker} it binds. The initializer's own
     * re-verification is what fails here (case 3b): the snapshot decodes structurally but its
     * integrity tag does not match. Per the origin-or-verified mint evidence rule (A9), the initializer
     * binds only the marker — never a fallback {@link SecurityContext} — since no origin proves the
     * dispatch is deferred execution.
     *
     * @param holder the context holder to bind against; the returned marker remains bound on it
     * @return the degradation marker bound by the initializer
     */
    private static SnapshotDegradationMarker bindTamperedSnapshotAndReconstruct(ContextHolder holder) {
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(hmac());
        dev.vertique.security.IdentitySnapshotContent content = new dev.vertique.security.IdentitySnapshotContent(
                new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of()),
                Optional.of(USER),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                java.util.List.of(),
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        Instant issuedAt = Instant.now();
        dev.vertique.security.IdentitySnapshot valid =
                codec.decode(codec.encode(new dev.vertique.security.IdentitySnapshot(
                        2,
                        content,
                        new dev.vertique.security.SnapshotCarrierBinding(
                                "carrier-1",
                                new dev.vertique.core.context.DurableTarget("outbox", "orders", Optional.empty())),
                        issuedAt,
                        issuedAt.plusSeconds(3600),
                        new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"))));

        dev.vertique.security.IdentitySnapshot tampered = new dev.vertique.security.IdentitySnapshot(
                valid.schemaVersion(),
                valid.content(),
                valid.carrier(),
                valid.issuedAt(),
                valid.expiresAt(),
                new SnapshotIntegrity(
                        valid.integrity().algorithm(),
                        valid.integrity().keyId(),
                        valid.integrity().tag() + "x"));

        dev.vertique.security.IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
        dev.vertique.security.runtime.ServiceIdentityResolver resolver =
                origin -> dev.vertique.security.SystemIdentities.scheduledJob(origin.reference());
        IdentitySnapshotReconstructionInitializer initializer = new IdentitySnapshotReconstructionInitializer(
                holder, reconstruction, Optional.of(resolver), identitySnapshotConfig());

        holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.verified(tampered));
        initializer.initialize(new InboundContextInitializationContext("delayed-job"));

        return holder.current(SnapshotDegradationMarker.class)
                .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
    }
}
