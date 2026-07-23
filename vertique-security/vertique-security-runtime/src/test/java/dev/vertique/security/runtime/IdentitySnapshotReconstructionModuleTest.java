// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.IdentitySnapshotFactory;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger-wiring test for {@link IdentitySnapshotReconstructionModule}.
 *
 * <p>Proves the module composes into a real Dagger graph (config-backed keyset →
 * {@link IdentitySnapshotCodec} → {@link IdentityReconstruction} → the initializer) and that
 * {@link IdentitySnapshotReconstructionInitializer} lands in the {@code Set<InboundContextInitializer>}
 * multibinding — the same multibinding {@code CorrelationContextSeeder} contributes to, and the one
 * {@code InboundExecutionContextScope} injects on every {@code SERVICE_DISPATCH} receive (PRD-ID-002
 * §14.6 slice P1.S5c).
 */
class IdentitySnapshotReconstructionModuleTest {

    @Test
    @DisplayName("initializer is contributed into the InboundContextInitializer multibinding set")
    void initializerContributedToMultibindingSet() {
        TestComponent component = DaggerIdentitySnapshotReconstructionModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();

        Set<InboundContextInitializer> initializers = component.inboundContextInitializers();

        assertTrue(
                initializers.stream().anyMatch(i -> i instanceof IdentitySnapshotReconstructionInitializer),
                "the multibinding set must contain the identity-snapshot reconstruction initializer");
    }

    @Test
    @DisplayName("IdentityReconstruction and IdentitySnapshotFactory resolve from the same graph")
    void supportingBindingsResolve() {
        TestComponent component = DaggerIdentitySnapshotReconstructionModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();

        assertNotNull(component.identityReconstruction(), "IdentityReconstruction must resolve");
        assertNotNull(component.identitySnapshotFactory(), "IdentitySnapshotFactory must resolve");
    }

    @Test
    @DisplayName("with no application binding the ServiceIdentityResolver Optional resolves to empty")
    void noAppBindingLeavesResolverOptionalEmpty() {
        TestComponent component = DaggerIdentitySnapshotReconstructionModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();

        Optional<ServiceIdentityResolver> resolver = component.serviceIdentityResolver();

        assertTrue(
                resolver.isEmpty(),
                "the framework binds no concrete ServiceIdentityResolver (only @BindsOptionalOf); absent an app "
                        + "binding the Optional is empty and the initializer uses its built-in default");
    }

    @Test
    @DisplayName("an application @Provides ServiceIdentityResolver overrides the built-in default")
    void appProvidedResolverOverridesDefault() {
        OverrideComponent component = DaggerIdentitySnapshotReconstructionModuleTest_OverrideComponent.builder()
                .testConfigModule(new TestConfigModule(validConfig()))
                .build();

        Optional<ServiceIdentityResolver> resolver = component.serviceIdentityResolver();

        assertTrue(resolver.isPresent(), "an app @Provides ServiceIdentityResolver must make the Optional present");
        var identity = resolver.orElseThrow().resolve(new DeferredExecutionOrigin("cron", "nightly"));
        assertEquals(
                "system:internal",
                identity.actor().id(),
                "the application-supplied resolver must win over the built-in scheduledJob default");
    }

    /**
     * Builds a minimal valid {@code identity.snapshot} config JSON carrying one active HMAC key.
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

    /** Minimal component including {@link IdentitySnapshotReconstructionModule} and its config deps. */
    @Singleton
    @Component(
            modules = {
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface TestComponent {

        /** Exposes the {@link InboundContextInitializer} multibinding set. */
        Set<InboundContextInitializer> inboundContextInitializers();

        /** Exposes the {@link IdentityReconstruction} binding. */
        IdentityReconstruction identityReconstruction();

        /** Exposes the {@link IdentitySnapshotFactory} binding (from {@code IdentitySnapshotCarriageModule}). */
        IdentitySnapshotFactory identitySnapshotFactory();

        /**
         * Exposes the optional {@link ServiceIdentityResolver} binding. Resolves to
         * {@link Optional#empty()} when no application binding is present (the framework declares only
         * {@code @BindsOptionalOf}).
         */
        Optional<ServiceIdentityResolver> serviceIdentityResolver();
    }

    /**
     * Component that adds an application {@link ServiceIdentityResolver} binding to prove the
     * {@code @BindsOptionalOf} seam lets an app override the built-in default.
     */
    @Singleton
    @Component(
            modules = {
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class,
                OverrideResolverModule.class
            })
    interface OverrideComponent {

        /** Exposes the optional {@link ServiceIdentityResolver}, now populated by the app binding. */
        Optional<ServiceIdentityResolver> serviceIdentityResolver();
    }

    /** Application module contributing a concrete {@link ServiceIdentityResolver} override. */
    @Module
    static final class OverrideResolverModule {

        /**
         * Provides an application {@link ServiceIdentityResolver} that resolves a {@code system:internal}
         * identity, distinct from the built-in {@code scheduledJob} default so the override is
         * observable.
         *
         * @return the overriding resolver; non-null
         */
        @Provides
        @Singleton
        ServiceIdentityResolver serviceIdentityResolver() {
            return origin -> SystemIdentities.internal(origin.reference());
        }
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
