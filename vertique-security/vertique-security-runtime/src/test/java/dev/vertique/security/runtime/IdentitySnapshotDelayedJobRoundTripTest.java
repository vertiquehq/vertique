// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.IdentitySnapshotFactory;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that identity-snapshot durable carriage round-trips through the <em>real</em>
 * substrate — the config-backed {@link DurableContextPropagator}, its
 * {@link dev.vertique.context.DurableContextMetadataRegistry}, and the
 * {@link InboundExecutionContextScope} that runs the registered
 * {@link IdentitySnapshotReconstructionInitializer} — when {@link IdentitySnapshotReconstructionModule}
 * is installed on a delayed-job-style Dagger component (PRD-ID-002 §14.3 "Durable carriage",
 * §14.6 slice P1.S5-ii).
 *
 * <p>Every collaborator is resolved from a live Dagger graph, never hand-rolled: capture binds an
 * {@link IdentitySnapshotContext} through the config-driven {@link IdentitySnapshotCapture}; the
 * producer side merges it into {@link DurableMetadata} via
 * {@link DurableContextPropagator#mergeCaptured(DurableMetadata, String)}; the consumer side decodes
 * that metadata via {@link DurableContextPropagator#decodeToDispatchContext(DurableMetadata, String)}
 * and installs it through {@link InboundExecutionContextScope#installDispatch(Map, String)}, which
 * runs the real reconstruction initializer. The postgres persist/poll hop is deferred (§13) — the
 * event-bus/native carrier is stood in for by feeding the propagator's decoded dispatch-context map
 * straight into the receive-side install, exactly the two operations
 * {@code DelayedJobService.toExecution} and {@code DelayedJobPoller} perform around it.
 *
 * <p>Producer and consumer run on <em>two distinct</em> duplicated Vert.x contexts because
 * {@link dev.vertique.context.DefaultContextHolder} stores its bindings in a per-Vert.x-context
 * slot (not per holder instance): the second duplicate gives the consumer a clean holder, modelling
 * the real producer-request / consumer-poll boundary.
 *
 * <ul>
 *   <li>{@link #captureEncodeDecodeReconstruct} — the happy path: a captured user is reconstructed
 *       as the deferred-execution subject-of-record under the scheduled-job actor.</li>
 *   <li>{@link #captureDisabledCarriesNoSnapshot} — the kill-switch: {@code captureEnabled=false}
 *       binds nothing, so no {@code identity-snapshot} namespace is carried.</li>
 *   <li>{@link #tamperedSnapshotDegrades} — degradation: a bound-but-unverifiable snapshot drives
 *       the initializer's case-3 path, binding a {@link SnapshotDegradationMarker}.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IdentitySnapshotDelayedJobRoundTripTest {

    private static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef USER =
            new PrincipalRef(PrincipalType.USER, "user-77", Map.of("realm", "acme-realm"));

    // Capture projects free-form attributes out (W2 credential-free enforcement); only type/id (+
    // allowlisted system.reason) survive the durable round trip.
    private static final PrincipalRef PROJECTED_USER = new PrincipalRef(PrincipalType.USER, "user-77", Map.of());

    @Test
    @DisplayName("capture -> real propagator merge -> decode -> InboundExecutionContextScope reconstructs the subject")
    void captureEncodeDecodeReconstruct(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        RoundTripComponent component = DaggerIdentitySnapshotDelayedJobRoundTripTest_RoundTripComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();
        ContextHolder holder = component.contextHolder();
        IdentitySnapshotCapture capture = component.identitySnapshotCapture();
        DurableContextPropagator propagator = component.durableContextPropagator();
        InboundExecutionContextScope executionScope = component.inboundExecutionContextScope();

        // F5/F3a row binding: mirrors DelayedJobService.toExecution / DelayedJobPoller.dispatch,
        // which thread a real per-row DurableCarrierDescriptor (carrierId = the execution id; target =
        // (delayed-job, the handler address)) rather than the carrier-less overloads. The delayed-job
        // boundary is wired with a real carrier, so its snapshot is NOT sentinel-signed and must still
        // reconstruct under the F3a fail-closed sentinel backstop.
        DurableCarrierDescriptor rowCarrier = new DurableCarrierDescriptor(
                "execution-1",
                new DurableTarget(DispatchBoundary.DELAYED_JOB, "job.delayed.test-handler", Optional.empty()));

        ContextInternal producerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        ContextInternal consumerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        producerCtx.runOnContext(v -> {
            DurableMetadata merged;
            try (ContextHolder.Scope captureScope = capture.captureFrom(liveUserContext())) {
                merged = propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB, rowCarrier);
            }
            DurableMetadata carrier = merged;

            testContext.verify(() -> {
                assertTrue(
                        carrier.has(IDENTITY_SNAPSHOT_NAMESPACE),
                        "merged metadata must carry the identity-snapshot namespace");
                assertTrue(
                        carrier.body(IDENTITY_SNAPSHOT_NAMESPACE).orElseThrow().getBoolean("present"),
                        "the identity-snapshot namespace body must flag present:true");
            });

            consumerCtx.runOnContext(v2 -> {
                Map<String, Object> dispatchContext =
                        propagator.decodeToDispatchContext(carrier, DispatchBoundary.DELAYED_JOB, rowCarrier);
                try (ContextHolder.Scope installed =
                        executionScope.installDispatch(dispatchContext, DispatchBoundary.DELAYED_JOB)) {
                    SecurityContext reconstructed =
                            holder.current(SecurityContext.class).orElseThrow();
                    testContext.verify(() -> {
                        assertEquals(
                                PROJECTED_USER,
                                reconstructed.identity().subject().orElseThrow(),
                                "reconstructed subject-of-record must equal the captured user with"
                                        + " free-form attributes projected out");
                        assertEquals(
                                "system:scheduledJob",
                                reconstructed.identity().actor().id(),
                                "the deferred executor identity is the scheduled-job system identity");
                        assertTrue(
                                IdentityReconstruction.isReconstructed(reconstructed),
                                "the reconstructed context must carry the reconstruction marker");
                        assertTrue(
                                holder.current(SnapshotDegradationMarker.class).isEmpty(),
                                "a clean round trip must not bind a degradation marker");
                    });
                }
                testContext.completeNow();
            });
        });
    }

    @Test
    @DisplayName("capture kill-switch off -> merged metadata carries no identity-snapshot namespace")
    void captureDisabledCarriesNoSnapshot(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        RoundTripComponent component = DaggerIdentitySnapshotDelayedJobRoundTripTest_RoundTripComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();
        ContextHolder holder = component.contextHolder();
        IdentitySnapshotFactory factory = component.identitySnapshotFactory();
        DurableContextPropagator propagator = component.durableContextPropagator();

        // The kill-switch is a property of the capture seam; construct it explicitly OFF over the
        // same real holder + factory rather than re-parsing config — the config->captureEnabled
        // wiring is covered by IdentitySnapshotCaptureTest.
        IdentitySnapshotCapture disabledCapture = new IdentitySnapshotCapture(holder, factory, false);

        ContextInternal producerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        producerCtx.runOnContext(v -> {
            DurableMetadata merged;
            try (ContextHolder.Scope captureScope = disabledCapture.captureFrom(liveUserContext())) {
                merged = propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB);
            }
            DurableMetadata carrier = merged;

            testContext.verify(() -> {
                assertTrue(
                        holder.current(IdentitySnapshotContext.class).isEmpty(),
                        "captureFrom must bind nothing when the kill-switch is off");
                assertFalse(
                        carrier.has(IDENTITY_SNAPSHOT_NAMESPACE),
                        "a disabled capture binds no snapshot, so no identity-snapshot namespace is carried");
            });
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("tampered snapshot at ingress -> InboundExecutionContextScope binds a BAD_HMAC degradation marker")
    void tamperedSnapshotDegrades(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        RoundTripComponent component = DaggerIdentitySnapshotDelayedJobRoundTripTest_RoundTripComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();
        ContextHolder holder = component.contextHolder();
        IdentitySnapshotCodec codec = component.identitySnapshotCodec();
        InboundExecutionContextScope executionScope = component.inboundExecutionContextScope();

        ContextInternal consumerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        consumerCtx.runOnContext(v -> {
            // A structurally-valid snapshot whose integrity tag has been tampered decodes into the
            // dispatch context but fails the initializer's re-verification (case 3). A real
            // DelayedJobPoller dispatch always carries a DeferredExecutionOrigin alongside the
            // decoded snapshot (see DelayedJobPoller.pollOnce, which puts both keys into the same
            // ctx map) — that origin is the deferred-execution provenance the origin-or-verified
            // mint evidence rule (A9) requires before an unverifiable snapshot may still mint a
            // fallback service context, so it is included here to mirror the real production shape.
            Map<String, Object> dispatchContext = Map.of(
                    IdentitySnapshotContext.class.getName(),
                    IdentitySnapshotContext.verified(tamperedSnapshot(codec)),
                    DeferredExecutionOrigin.class.getName(),
                    DeferredExecutionOrigin.of("delayed-job", "identity-snapshot-test-handler"));
            try (ContextHolder.Scope installed =
                    executionScope.installDispatch(dispatchContext, DispatchBoundary.DELAYED_JOB)) {
                SecurityContext bound = holder.current(SecurityContext.class).orElseThrow();
                SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                        .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                testContext.verify(() -> {
                    assertEquals(
                            "system:scheduledJob",
                            bound.identity().actor().id(),
                            "an unverifiable snapshot falls back to the scheduled-job service identity");
                    assertTrue(
                            bound.identity().subject().isEmpty(),
                            "an unverifiable snapshot must not carry over any subject");
                    assertEquals(
                            SnapshotDegradationReason.BAD_HMAC.name(),
                            marker.reasonCode(),
                            "a tampered integrity tag must bind a BAD_HMAC degradation marker");
                    assertFalse(
                            IdentityReconstruction.isReconstructed(bound),
                            "a degraded fallback context is not a reconstructed context");
                });
            }
            testContext.completeNow();
        });
    }

    // --- fixtures ---

    /**
     * Builds a live delegated {@link SecurityContext} whose identity carries {@code USER} as the
     * subject-of-record — the realistic shape for a deferred execution scheduled on behalf of an
     * authenticated user (mirrors the services-layer round-trip template).
     *
     * @return the live delegated {@link SecurityContext}
     */
    private static SecurityContext liveUserContext() {
        SecurityIdentity identity = new SecurityIdentity(ACTOR, Optional.of(USER), Optional.empty(), Optional.empty());
        return SecurityContexts.assemble(
                identity,
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    /**
     * Produces a properly signed snapshot via the real {@code codec}, then flips one character of
     * its integrity tag so the snapshot decodes structurally but fails HMAC re-verification.
     *
     * @param codec the config-backed codec used to sign the base snapshot; must not be {@code null}
     * @return a snapshot whose only defect is an invalid integrity tag
     */
    private static IdentitySnapshot tamperedSnapshot(IdentitySnapshotCodec codec) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                ACTOR,
                Optional.of(USER),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                List.of(),
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        Instant issuedAt = Instant.now();
        IdentitySnapshot valid = codec.decode(codec.encode(new IdentitySnapshot(
                2,
                content,
                new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty())),
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"))));
        return new IdentitySnapshot(
                valid.schemaVersion(),
                valid.content(),
                valid.carrier(),
                valid.issuedAt(),
                valid.expiresAt(),
                new SnapshotIntegrity(
                        valid.integrity().algorithm(),
                        valid.integrity().keyId(),
                        valid.integrity().tag() + "x"));
    }

    /**
     * Builds a minimal valid {@code identity.snapshot} config carrying one active HMAC key
     * (keyId {@code key-1}), matching the {@link #tamperedSnapshot(IdentitySnapshotCodec)} keyId so
     * a tamper fails only on the tag, never as UNKNOWN_KEY.
     *
     * @return the config {@link JsonObject}
     */
    private static JsonObject validConfig() {
        return new JsonObject()
                .put(
                        "identity",
                        new JsonObject()
                                .put(
                                        "snapshot",
                                        new JsonObject()
                                                .put(
                                                        "hmacKeys",
                                                        new JsonObject()
                                                                .put(
                                                                        "active",
                                                                        new JsonObject()
                                                                                .put("keyId", "key-1")
                                                                                .put(
                                                                                        "secretRef",
                                                                                        "super-secret-signing-key-material")))));
    }

    // --- test Dagger component ---

    /**
     * Delayed-job-style component: {@link IdentitySnapshotReconstructionModule} installed alongside
     * the config parser, exposing the real substrate collaborators the round trip drives.
     */
    @Singleton
    @Component(
            modules = {
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface RoundTripComponent {

        /** Exposes the shared {@link ContextHolder} the capture/propagator/initializer all bind through. */
        ContextHolder contextHolder();

        /** Exposes the real {@link DurableContextPropagator} backed by the config-driven registry. */
        DurableContextPropagator durableContextPropagator();

        /** Exposes the real {@link InboundExecutionContextScope} carrying the reconstruction initializer. */
        InboundExecutionContextScope inboundExecutionContextScope();

        /** Exposes the config-driven {@link IdentitySnapshotCapture} ingress seam. */
        IdentitySnapshotCapture identitySnapshotCapture();

        /** Exposes the {@link IdentitySnapshotFactory} used to construct a disabled capture in T3. */
        IdentitySnapshotFactory identitySnapshotFactory();

        /** Exposes the config-backed {@link IdentitySnapshotCodec} used to sign the T4 base snapshot. */
        IdentitySnapshotCodec identitySnapshotCodec();
    }

    /** Provides the {@code @VertxConfig JsonObject} from a caller-supplied value. */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        /**
         * Provides the application configuration as the {@code @VertxConfig} binding.
         *
         * @return the application configuration; non-null
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
