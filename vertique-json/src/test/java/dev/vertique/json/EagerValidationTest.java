// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that NFR-JSON-002A's eager validation seam works end-to-end through the Dagger graph.
 *
 * <p>Dagger constructs {@code @Singleton}s lazily — the first accessor call on the component forces
 * construction. This test verifies that:
 *
 * <ol>
 *   <li>A component with duplicate profile ids throws {@link JsonProfileConfigurationException} at
 *       the first accessor call (i.e. at bootstrap time, not at first request).
 *   <li>A component with a valid single profile resolves the {@code vertx} profile from its registry
 *       without throwing.
 * </ol>
 *
 * <p>This is a deterministic Dagger graph test — no network, no async — and runs via Maven Surefire
 * (unit tests) as a {@code *Test.java}.
 */
class EagerValidationTest {

    // --- Component + modules ---

    /**
     * Minimal Dagger component that installs {@link JsonRuntimeModule} and the supplied application
     * profiles module. The single accessor exposes the registry so callers can force construction.
     */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, BadProfilesModule.class})
    interface BadProfilesComponent {
        /**
         * Accessor that forces construction of the {@code @Singleton}
         * {@link DefaultJsonMapperProfileRegistry}. Calling this triggers validation.
         *
         * @return the registry (never reached when duplicate ids are present)
         */
        JsonMapperProfileRegistry registry();
    }

    /**
     * Test Dagger module that contributes TWO {@link JsonMapperProfile}s sharing the same id
     * ({@code payments-v1}) to prove that the duplicate-id rejection surfaces at the first accessor
     * call on a component that installs it.
     */
    @Module
    abstract static class BadProfilesModule {

        /**
         * Contributes the first {@code payments-v1} profile.
         *
         * @return a profile with id {@code payments-v1}
         */
        @Provides
        @IntoSet
        static JsonMapperProfile firstPaymentsProfile() {
            return JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        }

        /**
         * Contributes a second {@code payments-v1} profile — identical id, causing a duplicate.
         *
         * @return a second profile with the same id {@code payments-v1}
         */
        @Provides
        @IntoSet
        static JsonMapperProfile secondPaymentsProfile() {
            return JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        }
    }

    /**
     * Minimal Dagger component with one valid profile, used to prove the happy-path accessor
     * pattern works and the registry resolves the {@code vertx} profile.
     */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, GoodProfileModule.class})
    interface GoodProfileComponent {
        /**
         * Accessor that forces construction of the {@code @Singleton}
         * {@link DefaultJsonMapperProfileRegistry}.
         *
         * @return the fully-validated registry
         */
        JsonMapperProfileRegistry registry();
    }

    /**
     * Test Dagger module that contributes a single valid profile (id {@code payments-v1}) to prove
     * the happy-path bootstrap works without error.
     */
    @Module
    abstract static class GoodProfileModule {

        /**
         * Contributes a single valid {@code payments-v1} profile.
         *
         * @return a profile with id {@code payments-v1} backed by a {@link VertxJsonSupport}-aware
         *     mapper
         */
        @Provides
        @IntoSet
        static JsonMapperProfile paymentsProfile() {
            return JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("componentAccessor_forcesValidation_andFailsOnDuplicateIds")
    void componentAccessor_forcesValidation_andFailsOnDuplicateIds() {
        BadProfilesComponent component = DaggerEagerValidationTest_BadProfilesComponent.create();

        // Calling the accessor forces @Singleton construction, which runs the duplicate-id check
        // in DefaultJsonMapperProfileRegistry's @Inject constructor — the failure surfaces here,
        // at bootstrap time, before any request is served.
        assertThrows(
                JsonProfileConfigurationException.class,
                component::registry,
                "accessor must throw JsonProfileConfigurationException on duplicate profile ids");
    }

    @Test
    @DisplayName("componentAccessor_resolvesVertx_whenProfilesValid")
    void componentAccessor_resolvesVertx_whenProfilesValid() {
        GoodProfileComponent component = DaggerEagerValidationTest_GoodProfileComponent.create();

        // Happy path: accessor forces construction with valid profiles; vertx profile resolves.
        JsonMapperProfileRegistry registry =
                assertDoesNotThrow(component::registry, "accessor must not throw with valid profiles");

        assertSame(
                DatabindCodec.mapper(),
                registry.mapper(JsonProfileId.VERTX),
                "vertx profile must resolve to DatabindCodec.mapper()");
    }

    // --- Helpers ---

    /**
     * Builds an {@link ObjectMapper} with the Vert.x JSON type support module registered, suitable
     * for a profile mapper that must pass the round-trip probe.
     *
     * @return a mapper with {@link VertxJsonSupport#module()} registered
     */
    private static ObjectMapper vertxAwareMapper() {
        return new ObjectMapper().registerModule(VertxJsonSupport.module());
    }
}
