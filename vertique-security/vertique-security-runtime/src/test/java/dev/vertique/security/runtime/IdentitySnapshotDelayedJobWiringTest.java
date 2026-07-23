// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger-wiring proof that {@link IdentitySnapshotReconstructionModule}, installed on a delayed-job
 * -style component, composes standalone into a working durable-carriage graph (PRD-ID-002 §14.3
 * "Durable carriage", §14.6 slice P1.S5-ii).
 *
 * <p>Asserts that the substrate's {@link DurableContextMetadataRegistry} — the boot-time-validated
 * registry {@code DurableContextPropagator} injects — reports both the identity-snapshot durable
 * encoder and decoder under the {@code identity-snapshot} namespace, and that the reconstruction
 * initializer lands in the {@code Set<InboundContextInitializer>} that
 * {@code InboundExecutionContextScope} runs on every deferred-execution receive. The full
 * delayed-job component composition (with {@code DelayedJobModule} + {@code DispatchModule} + all
 * app modules) is proven separately by the {@code vertique-example-webhook} Dagger component
 * compiling with this module installed.
 */
class IdentitySnapshotDelayedJobWiringTest {

    private static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    @Test
    @DisplayName("registry reports the identity encoder+decoder and the reconstruction initializer is registered")
    void graphResolvesWithIdentityCarriage() {
        WiringComponent component = DaggerIdentitySnapshotDelayedJobWiringTest_WiringComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();

        DurableContextMetadataRegistry registry = component.durableContextMetadataRegistry();

        assertTrue(
                registry.encoders().stream()
                        .anyMatch(e -> e instanceof IdentitySnapshotDurableEncoder
                                && e.namespace().equals(IDENTITY_SNAPSHOT_NAMESPACE)
                                && e.type().equals(IdentitySnapshotContext.class)),
                "the durable registry must report the identity-snapshot encoder");

        assertTrue(
                registry.decoders().stream()
                        .anyMatch(d -> d instanceof IdentitySnapshotDurableDecoder
                                && d.namespace().equals(IDENTITY_SNAPSHOT_NAMESPACE)
                                && d.type().equals(IdentitySnapshotContext.class)),
                "the durable registry must report the identity-snapshot decoder");

        assertTrue(
                component.inboundContextInitializers().stream()
                        .anyMatch(i -> i instanceof IdentitySnapshotReconstructionInitializer),
                "the InboundContextInitializer set must contain the identity-snapshot reconstruction initializer");
    }

    /**
     * Builds a minimal valid {@code identity.snapshot} config carrying one active HMAC key.
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
     * Minimal delayed-job-style component: {@link IdentitySnapshotReconstructionModule} plus the
     * config parser, exposing the durable registry and the inbound-initializer set.
     */
    @Singleton
    @Component(
            modules = {
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface WiringComponent {

        /** Exposes the boot-validated durable metadata registry the propagator injects. */
        DurableContextMetadataRegistry durableContextMetadataRegistry();

        /** Exposes the {@link InboundContextInitializer} multibinding set. */
        Set<InboundContextInitializer> inboundContextInitializers();
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
