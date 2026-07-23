// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 2.1 RED tests for {@link JsonDefaultProfileValidator} — the global-default fail-fast seam.
 *
 * <p>Mirrors {@code EagerValidationTest} / {@code ComposeValidationStepTest}: a real Dagger component
 * installs {@link JsonRuntimeModule} plus a test module supplying {@code @VertxConfig JsonObject} and a
 * minimal {@link ConfigParser}. Resolving the {@code Set<ComposeValidator>} multibinding forces
 * construction of {@link JsonDefaultProfileValidator}, running its {@code @Inject} constructor — the
 * same materialization path {@code ComposeValidationStep} drives in the {@code VALIDATE} phase.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>an unregistered {@code json.jsonProfile} id throws {@link JsonProfileConfigurationException}
 *       when the validator set is forced (FR-JSON-050);
 *   <li>a known id ({@code vertique}) constructs cleanly;
 *   <li>an unset {@code json.jsonProfile} constructs cleanly (the validator no-ops on blank/absent).
 * </ul>
 */
@DisplayName("JsonDefaultProfileValidator")
class JsonDefaultProfileValidatorTest {

    @Test
    @DisplayName("unknown global id fails fast at VALIDATE")
    void unknownGlobalIdFailsFast() {
        UnknownComponent component = DaggerJsonDefaultProfileValidatorTest_UnknownComponent.create();

        // Forcing the Set<ComposeValidator> constructs JsonDefaultProfileValidator, whose @Inject
        // ctor must resolve json.jsonProfile through the registry — an unregistered id throws.
        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "an unregistered json.jsonProfile id must fail fast when the validator set is forced");
    }

    @Test
    @DisplayName("known global id passes")
    void knownGlobalIdPasses() {
        KnownComponent component = DaggerJsonDefaultProfileValidatorTest_KnownComponent.create();

        assertDoesNotThrow(
                component::composeValidators, "a known json.jsonProfile id (vertique) must construct cleanly");
    }

    @Test
    @DisplayName("unset global id passes")
    void unsetGlobalIdPasses() {
        UnsetComponent component = DaggerJsonDefaultProfileValidatorTest_UnsetComponent.create();

        assertDoesNotThrow(component::composeValidators, "an unset json.jsonProfile must no-op (blank ⇒ skip)");
    }

    // --- Components ---

    /** Component whose {@code json.jsonProfile} names an id no profile is registered for. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnknownConfigModule.class})
    interface UnknownComponent {
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code json.jsonProfile} is the reserved built-in {@code vertique} id. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, KnownConfigModule.class})
    interface KnownComponent {
        Set<ComposeValidator> composeValidators();
    }

    /** Component with no {@code json.jsonProfile} set. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnsetConfigModule.class})
    interface UnsetComponent {
        Set<ComposeValidator> composeValidators();
    }

    // --- Test config modules ---

    /** Supplies a root config whose {@code json.jsonProfile} is an unregistered id. */
    @Module
    abstract static class UnknownConfigModule {

        /**
         * @return a root config with {@code json.jsonProfile} set to an unregistered id
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return rootWithJsonProfile("no-such-profile");
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }
    }

    /** Supplies a root config whose {@code json.jsonProfile} is the reserved {@code vertique} id. */
    @Module
    abstract static class KnownConfigModule {

        /**
         * @return a root config with {@code json.jsonProfile} set to {@code vertique}
         */
        @Provides
        @VertxConfig
        static JsonObject rootConfig() {
            return rootWithJsonProfile("vertique");
        }

        /**
         * @return the minimal test config parser
         */
        @Provides
        static ConfigParser configParser() {
            return plainConfigParser();
        }
    }

    /** Supplies a root config with no {@code json} section. */
    @Module
    abstract static class UnsetConfigModule {

        /**
         * @return an empty root config (no {@code json} section)
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

    // --- Helpers ---

    /**
     * Builds a root config object carrying {@code json.jsonProfile = profileId}.
     *
     * @param profileId the global default profile id to set under the {@code json} section
     * @return the root config object
     */
    private static JsonObject rootWithJsonProfile(String profileId) {
        return new JsonObject().put("json", new JsonObject().put("jsonProfile", profileId));
    }

    /**
     * Returns a minimal {@link ConfigParser} over a plain Jackson {@link ObjectMapper}, sufficient to
     * bind the simple {@link JsonConfig} record through its {@code @JsonCreator}.
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
                throw new UnsupportedOperationException("not needed for JsonDefaultProfileValidatorTest");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed for JsonDefaultProfileValidatorTest");
            }
        };
    }
}
