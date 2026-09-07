// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

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
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.json.JsonRuntimeModule;
import dev.vertique.rest.client.config.RestClientDefaults;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RestClientDefaultProfileValidator} — the rest-client per-boundary fail-fast
 * seam for {@code restClient.defaults.jsonProfile}.
 *
 * <p>Mirrors {@code JaxRsDefaultProfileValidatorTest} in {@code vertique-rest-jaxrs}: a real Dagger
 * component installs {@link JsonRuntimeModule} (for the
 * {@link dev.vertique.core.json.JsonMapperProfileRegistry}) plus a test module supplying a minimal
 * {@link ConfigParser}, {@code @VertxConfig JsonObject}, the resolved {@link RestClientDefaults}
 * (injected directly into the validator), and the {@link RestClientDefaultProfileValidator}
 * {@code @IntoSet} contribution. Resolving the {@code Set<ComposeValidator>} multibinding forces
 * construction of {@link RestClientDefaultProfileValidator}, running its {@code @Inject} constructor
 * — the same materialization path {@code ComposeValidationStep} drives in the {@code VALIDATE}
 * phase.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>an unregistered {@code restClient.defaults.jsonProfile} id throws
 *       {@link JsonProfileConfigurationException} when the validator set is forced — even with
 *       <strong>zero</strong> clients configured (the inert-boundary case FR-JSON-050 explicitly
 *       closes);</li>
 *   <li>shadowing: a bad {@code restClient.defaults.jsonProfile} still fails fast even when a valid
 *       per-client {@code jsonProfile} could shadow it at runtime — the validator validates the
 *       configured default <em>independently</em>;</li>
 *   <li>a known id ({@code system}) passes;</li>
 *   <li>an unset {@code restClient.defaults.jsonProfile} passes (the validator no-ops on
 *       blank/absent).</li>
 * </ul>
 */
@DisplayName("RestClientDefaultProfileValidator")
class RestClientDefaultProfileValidatorTest {

    @Test
    @DisplayName("unknown restClient.defaults.jsonProfile fails fast at VALIDATE even with zero clients (inert)")
    void unknownDefaultsProfileFailsFast() {
        // Forcing Set<ComposeValidator> constructs RestClientDefaultProfileValidator, whose @Inject
        // ctor must resolve the defaults id through the registry — an unregistered id throws.
        // Zero clients are configured: the validator is independent of any client config.
        UnknownComponent component = DaggerRestClientDefaultProfileValidatorTest_UnknownComponent.create();

        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "an unregistered restClient.defaults.jsonProfile id must fail fast when the validator set is forced");
    }

    @Test
    @DisplayName("shadowing: a valid per-client profile does NOT mask a bad restClient.defaults.jsonProfile")
    void shadowingDoesNotMaskBadDefault() {
        // The validator only sees the defaults record + the registry — never any per-client config.
        // A bad defaults id fails fast regardless of whether a per-client profile would shadow it
        // at runtime.
        ShadowComponent component = DaggerRestClientDefaultProfileValidatorTest_ShadowComponent.create();

        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "a bad restClient.defaults.jsonProfile must fail fast even when a per-client profile could shadow it");
    }

    @Test
    @DisplayName("known restClient.defaults.jsonProfile (system) passes")
    void knownDefaultsProfilePasses() {
        KnownComponent component = DaggerRestClientDefaultProfileValidatorTest_KnownComponent.create();

        assertDoesNotThrow(
                component::composeValidators,
                "a known restClient.defaults.jsonProfile id (system) must construct cleanly");
    }

    @Test
    @DisplayName("unset restClient.defaults.jsonProfile passes (no-op on blank/absent)")
    void unsetDefaultsProfilePasses() {
        UnsetComponent component = DaggerRestClientDefaultProfileValidatorTest_UnsetComponent.create();

        assertDoesNotThrow(
                component::composeValidators, "an unset restClient.defaults.jsonProfile must no-op (blank ⇒ skip)");
    }

    // --- Components ---

    /** Component whose {@code restClient.defaults.jsonProfile} names an unregistered id. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnknownDefaultsModule.class})
    interface UnknownComponent {
        /** Forces construction of all {@link ComposeValidator} singletons. */
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code restClient.defaults.jsonProfile} is a bad id (shadowing case). */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, ShadowDefaultsModule.class})
    interface ShadowComponent {
        /** Forces construction of all {@link ComposeValidator} singletons. */
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code restClient.defaults.jsonProfile} is the reserved {@code system} id. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, KnownDefaultsModule.class})
    interface KnownComponent {
        /** Forces construction of all {@link ComposeValidator} singletons. */
        Set<ComposeValidator> composeValidators();
    }

    /** Component with no {@code restClient.defaults.jsonProfile} set. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnsetDefaultsModule.class})
    interface UnsetComponent {
        /** Forces construction of all {@link ComposeValidator} singletons. */
        Set<ComposeValidator> composeValidators();
    }

    // --- Test config modules ---

    /**
     * Supplies the infrastructure bindings shared by all test config modules: the empty root config
     * and a minimal {@link ConfigParser}.
     */
    @Module
    abstract static class InfraModule {

        /**
         * @return the empty root config (the registry needs no {@code json} section)
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
    }

    /** Supplies a {@link RestClientDefaults} with an unregistered profile id. */
    @Module(includes = InfraModule.class)
    abstract static class UnknownDefaultsModule {

        /**
         * @param impl the rest-client default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator restClientDefaultsValidator(RestClientDefaultProfileValidator impl) {
            return impl;
        }

        /**
         * Provides {@link RestClientDefaults} with an unregistered profile id.
         *
         * @return a {@link RestClientDefaults} carrying the unregistered id {@code "no-such-profile"}
         */
        @Provides
        @Singleton
        static RestClientDefaults restClientDefaults() {
            return new RestClientDefaults("no-such-profile");
        }
    }

    /**
     * Supplies a {@link RestClientDefaults} with a bad profile id (shadowing case: a valid
     * per-client profile could shadow it, but the validator must still fail fast).
     */
    @Module(includes = InfraModule.class)
    abstract static class ShadowDefaultsModule {

        /**
         * @param impl the rest-client default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator restClientDefaultsValidator(RestClientDefaultProfileValidator impl) {
            return impl;
        }

        /**
         * Provides {@link RestClientDefaults} with a bad (unregistered) profile id.
         *
         * @return a {@link RestClientDefaults} carrying the unregistered id {@code "bad-default"}
         */
        @Provides
        @Singleton
        static RestClientDefaults restClientDefaults() {
            return new RestClientDefaults("bad-default");
        }
    }

    /**
     * Supplies a {@link RestClientDefaults} with the reserved {@code system} profile id (known).
     */
    @Module(includes = InfraModule.class)
    abstract static class KnownDefaultsModule {

        /**
         * @param impl the rest-client default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator restClientDefaultsValidator(RestClientDefaultProfileValidator impl) {
            return impl;
        }

        /**
         * Provides {@link RestClientDefaults} with the reserved {@code system} id (always
         * registered).
         *
         * @return a {@link RestClientDefaults} carrying the {@code "system"} id
         */
        @Provides
        @Singleton
        static RestClientDefaults restClientDefaults() {
            return new RestClientDefaults("system");
        }
    }

    /**
     * Supplies a {@link RestClientDefaults} with a {@code null} profile id (not set).
     */
    @Module(includes = InfraModule.class)
    abstract static class UnsetDefaultsModule {

        /**
         * @param impl the rest-client default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator restClientDefaultsValidator(RestClientDefaultProfileValidator impl) {
            return impl;
        }

        /**
         * Provides {@link RestClientDefaults} with a {@code null} profile id (no
         * {@code restClient.defaults} sub-object present in config).
         *
         * @return {@link RestClientDefaults#defaults()} — null jsonProfile
         */
        @Provides
        @Singleton
        static RestClientDefaults restClientDefaults() {
            return RestClientDefaults.defaults();
        }
    }

    // --- Helpers ---

    /**
     * Returns a minimal {@link ConfigParser} over a plain Jackson {@link ObjectMapper}, sufficient to
     * bind the simple {@link dev.vertique.json.JsonConfig} record required transitively by
     * {@link JsonRuntimeModule}.
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
                throw new UnsupportedOperationException("not needed for RestClientDefaultProfileValidatorTest");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed for RestClientDefaultProfileValidatorTest");
            }
        };
    }
}
