// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.rest.core.config.JaxRsConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 2.2 RED tests for {@link JaxRsDefaultProfileValidator} — the JAX-RS per-boundary fail-fast
 * seam for {@code jaxrs.jsonProfile}.
 *
 * <p>Mirrors slice 2.1's {@code JsonDefaultProfileValidatorTest}: a real Dagger component installs
 * {@link JsonRuntimeModule} (for the {@link JsonMapperProfileRegistry}) plus a test module supplying
 * {@code @VertxConfig JsonObject}, a minimal {@link ConfigParser}, the {@link JaxRsConfig} carrying
 * {@code jaxrs.jsonProfile}, and the {@link JaxRsDefaultProfileValidator} {@code @IntoSet}
 * contribution. Resolving the {@code Set<ComposeValidator>} multibinding forces construction of
 * {@link JaxRsDefaultProfileValidator}, running its {@code @Inject} constructor — the same
 * materialization path {@code ComposeValidationStep} drives in the {@code VALIDATE} phase.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>an unregistered {@code jaxrs.jsonProfile} id throws {@link JsonProfileConfigurationException}
 *       when the validator set is forced — even with zero routes registered (the validator is inert
 *       of any route state, FR-JSON-050);
 *   <li>shadowing: a bad {@code jaxrs.jsonProfile} still fails fast even though a per-method/class
 *       {@code @JsonProfile} could shadow it at a route — the validator validates the configured
 *       default independently;
 *   <li>a known id ({@code vertx}) constructs cleanly;
 *   <li>an unset {@code jaxrs.jsonProfile} constructs cleanly (the validator no-ops on blank/absent).
 * </ul>
 */
@DisplayName("JaxRsDefaultProfileValidator")
class JaxRsDefaultProfileValidatorTest {

    @Test
    @DisplayName("unknown jaxrs.jsonProfile fails fast at VALIDATE even with zero routes (inert)")
    void unknownJaxRsProfileFailsFast() {
        UnknownComponent component = DaggerJaxRsDefaultProfileValidatorTest_UnknownComponent.create();

        // Forcing the Set<ComposeValidator> constructs JaxRsDefaultProfileValidator, whose @Inject
        // ctor must resolve jaxrs.jsonProfile through the registry — an unregistered id throws.
        // No routes are registered here: the validator is independent of route state.
        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "an unregistered jaxrs.jsonProfile id must fail fast when the validator set is forced");
    }

    @Test
    @DisplayName("shadowing: a valid per-binding @JsonProfile does not mask a bad jaxrs.jsonProfile")
    void shadowingDoesNotMaskBadDefault() {
        // The validator only sees JaxRsConfig + the registry — never any route's @JsonProfile. So a bad
        // jaxrs.jsonProfile fails fast regardless of whether a more-specific annotation could shadow it
        // at a route. This component sets a bad jaxrs.jsonProfile and registers no routes; forcing the
        // validator set must still throw.
        ShadowComponent component = DaggerJaxRsDefaultProfileValidatorTest_ShadowComponent.create();

        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "a bad jaxrs.jsonProfile must fail fast even when a per-binding @JsonProfile could shadow it");
    }

    @Test
    @DisplayName("known jaxrs.jsonProfile (vertx) passes")
    void knownJaxRsProfilePasses() {
        KnownComponent component = DaggerJaxRsDefaultProfileValidatorTest_KnownComponent.create();

        assertDoesNotThrow(component::composeValidators, "a known jaxrs.jsonProfile id (vertx) must construct cleanly");
    }

    @Test
    @DisplayName("unset jaxrs.jsonProfile passes")
    void unsetJaxRsProfilePasses() {
        UnsetComponent component = DaggerJaxRsDefaultProfileValidatorTest_UnsetComponent.create();

        assertDoesNotThrow(component::composeValidators, "an unset jaxrs.jsonProfile must no-op (blank ⇒ skip)");
    }

    // --- Components ---

    /** Component whose {@code jaxrs.jsonProfile} names an id no profile is registered for. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnknownConfigModule.class})
    interface UnknownComponent {
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code jaxrs.jsonProfile} is a bad id (shadowing case — no routes registered). */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, ShadowConfigModule.class})
    interface ShadowComponent {
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code jaxrs.jsonProfile} is the reserved built-in {@code vertx} id. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, KnownConfigModule.class})
    interface KnownComponent {
        Set<ComposeValidator> composeValidators();
    }

    /** Component with no {@code jaxrs.jsonProfile} set. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnsetConfigModule.class})
    interface UnsetComponent {
        Set<ComposeValidator> composeValidators();
    }

    // --- Test config modules ---

    /** Supplies a {@link JaxRsConfig} whose {@code jaxrs.jsonProfile} is an unregistered id. */
    @Module
    abstract static class UnknownConfigModule {

        /**
         * @return a {@link JaxRsConfig} with {@code jsonProfile} set to an unregistered id
         */
        @Provides
        static JaxRsConfig jaxRsConfig() {
            return JaxRsConfig.builder().jsonProfile("no-such-profile").build();
        }

        /**
         * @return the empty root config (the registry needs no {@code json} section here)
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return new JsonObject();
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }

        /**
         * @param impl the JAX-RS default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator jaxRsValidator(JaxRsDefaultProfileValidator impl) {
            return impl;
        }
    }

    /** Supplies a {@link JaxRsConfig} whose {@code jaxrs.jsonProfile} is a bad id (shadowing case). */
    @Module
    abstract static class ShadowConfigModule {

        /**
         * @return a {@link JaxRsConfig} with {@code jsonProfile} set to a bad id
         */
        @Provides
        static JaxRsConfig jaxRsConfig() {
            return JaxRsConfig.builder().jsonProfile("bad-default").build();
        }

        /**
         * @return the empty root config
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return new JsonObject();
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }

        /**
         * @param impl the JAX-RS default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator jaxRsValidator(JaxRsDefaultProfileValidator impl) {
            return impl;
        }
    }

    /** Supplies a {@link JaxRsConfig} whose {@code jaxrs.jsonProfile} is the reserved {@code vertx} id. */
    @Module
    abstract static class KnownConfigModule {

        /**
         * @return a {@link JaxRsConfig} with {@code jsonProfile} set to {@code vertx}
         */
        @Provides
        static JaxRsConfig jaxRsConfig() {
            return JaxRsConfig.builder().jsonProfile("vertx").build();
        }

        /**
         * @return the empty root config
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return new JsonObject();
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }

        /**
         * @param impl the JAX-RS default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator jaxRsValidator(JaxRsDefaultProfileValidator impl) {
            return impl;
        }
    }

    /** Supplies a {@link JaxRsConfig} with no {@code jaxrs.jsonProfile} set. */
    @Module
    abstract static class UnsetConfigModule {

        /**
         * @return a default {@link JaxRsConfig} (no {@code jsonProfile})
         */
        @Provides
        static JaxRsConfig jaxRsConfig() {
            return JaxRsConfig.builder().build();
        }

        /**
         * @return the empty root config
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return new JsonObject();
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }

        /**
         * @param impl the JAX-RS default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator jaxRsValidator(JaxRsDefaultProfileValidator impl) {
            return impl;
        }
    }

    // --- Helpers ---

    /**
     * Returns a minimal {@link ConfigParser} over a plain Jackson {@link ObjectMapper}, sufficient to
     * bind the simple {@code JsonConfig} record (required transitively by {@link JsonRuntimeModule}).
     *
     * @return a {@link ConfigParser} whose {@code parse} delegates to a plain {@link ObjectMapper}
     */
    private static ConfigParser plainConfigParser() {
        ObjectMapper mapper = new ObjectMapper();
        return new ConfigParser() {
            @Override
            public <T> T parse(JsonObject section, Class<T> type) {
                try {
                    String json = section == null ? "{}" : section.encode();
                    return mapper.readValue(json, type);
                } catch (Exception e) {
                    throw new ConfigurationException("failed to parse config section into " + type.getSimpleName(), e);
                }
            }

            @Override
            public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                throw new UnsupportedOperationException("not needed for JaxRsDefaultProfileValidatorTest");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed for JaxRsDefaultProfileValidatorTest");
            }
        };
    }
}
