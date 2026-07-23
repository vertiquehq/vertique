// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Boundary-parity sibling of {@link IdentitySnapshotDelayedJobRoundTripTest}, using the same live
 * Dagger graph — {@link IdentitySnapshotReconstructionModule} installed, the real
 * {@link DurableContextPropagator} and its {@link dev.vertique.context.DurableContextMetadataRegistry},
 * the config-driven {@link IdentitySnapshotCapture} ingress seam, and the real
 * {@link InboundExecutionContextScope} that runs the registered
 * {@link IdentitySnapshotReconstructionInitializer} — across the outbox constants,
 * {@link DispatchBoundary#OUTBOX} (producer / {@code mergeCaptured} side) &rarr;
 * {@link DispatchBoundary#OUTBOX_SERVICE} (relay / {@code decodeToDispatchContext} side)
 * (PRD-ID-002 §14.3 "Durable carriage", §14.6 slice P1.S5-iii; §14.6 amendment F3a fail-closed
 * sentinel backstop; F3b real per-row outbox carrier binding).
 *
 * <ul>
 *   <li>{@link #captureEncodeDecodeReconstruct} — the happy path: production outbox wiring
 *       ({@code DefaultOutboxService}, {@code ServiceOutboxDestinationHandler}) threads a real
 *       per-row {@link DurableCarrierDescriptor} (carrierId = the outbox row's {@code carrier_id};
 *       target = ({@code outbox-relay}, carrierId)) on both the produce and consume side, so the
 *       captured snapshot is NOT sentinel-signed and reconstructs the captured subject.
 *   <li>{@link #carrierLessCallDegradesViaSentinelBackstop} — defense-in-depth: a caller that
 *       bypasses the carrier-aware overloads (as production did before F3b) still signs the fixed
 *       {@code "unbound"} sentinel carrier, and {@link IdentitySnapshotDurableDecoder}'s F3a
 *       fail-closed sentinel backstop makes it unconditionally unverifiable — proving the substrate
 *       fails closed independent of whether a boundary remembers to thread its real carrier.
 * </ul>
 *
 * <p>Producer and consumer run on <em>two distinct</em> duplicated Vert.x contexts because
 * {@link dev.vertique.context.DefaultContextHolder} stores its bindings in a per-Vert.x-context slot
 * (not per holder instance): the second duplicate gives the consumer a clean holder, modelling the
 * real producer-request / consumer-relay boundary.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IdentitySnapshotOutboxRoundTripTest {

    private static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef USER =
            new PrincipalRef(PrincipalType.USER, "user-77", Map.of("realm", "acme-realm"));

    // Capture projects free-form attributes out (W2 credential-free enforcement); only type/id (+
    // allowlisted system.reason) survive the durable round trip.
    private static final PrincipalRef PROJECTED_USER = new PrincipalRef(PrincipalType.USER, "user-77", Map.of());

    @Test
    @DisplayName("capture -> real propagator merge(OUTBOX, carrier) -> decode(OUTBOX_SERVICE, carrier) ->"
            + " InboundExecutionContextScope reconstructs the subject (F3b real per-row outbox carrier)")
    void captureEncodeDecodeReconstruct(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        RoundTripComponent component = DaggerIdentitySnapshotOutboxRoundTripTest_RoundTripComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();
        ContextHolder holder = component.contextHolder();
        IdentitySnapshotCapture capture = component.identitySnapshotCapture();
        DurableContextPropagator propagator = component.durableContextPropagator();
        InboundExecutionContextScope executionScope = component.inboundExecutionContextScope();

        // F3b row binding: mirrors DefaultOutboxService.publish / ServiceOutboxDestinationHandler.publish,
        // which thread a real per-row DurableCarrierDescriptor (carrierId = the outbox row's carrier_id;
        // target = (outbox-relay, carrierId)) rather than the carrier-less overloads.
        UUID carrierId = UUID.randomUUID();
        DurableCarrierDescriptor rowCarrier = new DurableCarrierDescriptor(
                carrierId.toString(), new DurableTarget("outbox-relay", carrierId.toString(), Optional.empty()));

        ContextInternal producerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        ContextInternal consumerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        producerCtx.runOnContext(v -> {
            DurableMetadata merged;
            try (ContextHolder.Scope captureScope = capture.captureFrom(liveUserContext())) {
                merged = propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.OUTBOX, rowCarrier);
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
                        propagator.decodeToDispatchContext(carrier, DispatchBoundary.OUTBOX_SERVICE, rowCarrier);
                try (ContextHolder.Scope installed =
                        executionScope.installDispatch(dispatchContext, DispatchBoundary.OUTBOX_SERVICE)) {
                    SecurityContext reconstructed =
                            holder.current(SecurityContext.class).orElseThrow();
                    testContext.verify(() -> {
                        assertEquals(
                                PROJECTED_USER,
                                reconstructed.identity().subject().orElseThrow(),
                                "reconstructed subject-of-record must equal the captured user with"
                                        + " free-form attributes projected out");
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
    @DisplayName("capture -> real propagator merge(OUTBOX) -> decode(OUTBOX_SERVICE) [carrier-less overloads] ->"
            + " InboundExecutionContextScope fails closed (F3a): a caller that bypasses the carrier-aware"
            + " overloads still signs the fixed sentinel carrier, so decode is unconditionally unverifiable")
    void carrierLessCallDegradesViaSentinelBackstop(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        RoundTripComponent component = DaggerIdentitySnapshotOutboxRoundTripTest_RoundTripComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();
        ContextHolder holder = component.contextHolder();
        IdentitySnapshotCapture capture = component.identitySnapshotCapture();
        DurableContextPropagator propagator = component.durableContextPropagator();
        InboundExecutionContextScope executionScope = component.inboundExecutionContextScope();

        ContextInternal producerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        ContextInternal consumerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        producerCtx.runOnContext(v -> {
            DurableMetadata merged;
            try (ContextHolder.Scope captureScope = capture.captureFrom(liveUserContext())) {
                merged = propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.OUTBOX);
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
                        propagator.decodeToDispatchContext(carrier, DispatchBoundary.OUTBOX_SERVICE);
                try (ContextHolder.Scope installed =
                        executionScope.installDispatch(dispatchContext, DispatchBoundary.OUTBOX_SERVICE)) {
                    testContext.verify(() -> {
                        // F3a fail-closed sentinel backstop: mergeCaptured/decodeToDispatchContext above
                        // use the carrier-less overloads (the outbox boundary is not yet wired with a real
                        // per-row DurableCarrierDescriptor — that is F3b, tracked separately), so the
                        // captured snapshot is signed with the fixed "unbound" sentinel carrier. The decoder
                        // now treats a sentinel-signed snapshot as unconditionally unverifiable, so this
                        // dispatch must degrade rather than reconstruct the captured subject — origin-absent
                        // case 3a of IdentitySnapshotReconstructionInitializer binds only the degradation
                        // marker, no SecurityContext.
                        assertTrue(
                                holder.current(SecurityContext.class).isEmpty(),
                                "an unwired (sentinel-signed) outbox snapshot must never reconstruct a"
                                        + " SecurityContext");
                        SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                                .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                        assertEquals(
                                SnapshotDegradationReason.DECODE_FAILED.name(),
                                marker.reasonCode(),
                                "a sentinel-signed snapshot must degrade with the DECODE_FAILED reason (F3a)");
                    });
                }
                testContext.completeNow();
            });
        });
    }

    // --- fixtures ---

    /**
     * Builds a live delegated {@link SecurityContext} whose identity carries {@code USER} as the
     * subject-of-record — the realistic shape for a deferred execution scheduled on behalf of an
     * authenticated user (mirrors the delayed-job round-trip template).
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
     * Builds a minimal valid {@code identity.snapshot} config carrying one active HMAC key
     * (keyId {@code key-1}) so the capture seam can sign and the receive side can verify.
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
     * Outbox-style component: {@link IdentitySnapshotReconstructionModule} installed alongside the
     * config parser, exposing the real substrate collaborators the round trip drives.
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
