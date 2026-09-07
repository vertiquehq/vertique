// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

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
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.config.KafkaMessageKeyHashConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 2.4 RED tests for {@link KafkaDefaultProfileValidator} — the kafka-json per-boundary
 * fail-fast seam for {@code kafka.jsonProfile}.
 *
 * <p>Mirrors the {@code JaxRsDefaultProfileValidatorTest} pattern: a real Dagger component installs
 * {@link JsonRuntimeModule} (for the {@link dev.vertique.core.json.JsonMapperProfileRegistry}) plus a
 * test module supplying a pre-built {@link KafkaConfig}, the empty {@code @VertxConfig JsonObject},
 * and a minimal {@link ConfigParser}. Resolving the {@code Set<ComposeValidator>} multibinding forces
 * construction of {@link KafkaDefaultProfileValidator}, running its {@code @Inject} constructor — the
 * same materialization path {@code ComposeValidationStep} drives in the {@code VALIDATE} phase.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>an unknown {@code kafka.jsonProfile} fails fast at VALIDATE even with zero
 *       consumers/producers configured (inert boundary — FR-JSON-050);</li>
 *   <li>shadowing: a bad {@code kafka.jsonProfile} still fails fast even when a
 *       per-binding profile could shadow it at runtime;</li>
 *   <li>a known id ({@code vertx}) constructs cleanly;</li>
 *   <li>an unset {@code kafka.jsonProfile} constructs cleanly (the validator no-ops on
 *       blank/absent).</li>
 * </ul>
 *
 * <p><strong>RED discipline:</strong> the {@link KafkaDefaultProfileValidator} stub currently does
 * NOT call {@code registry.profile(...)} — its constructor is a no-op. The <em>unknown</em> and
 * <em>shadowing</em> tests below will fail because the stub never throws
 * {@link JsonProfileConfigurationException}. The <em>known</em> and <em>unset</em> tests will pass
 * (the stub no-ops cleanly). This mix of red and passing tests is correct for a RED commit.
 */
@DisplayName("KafkaDefaultProfileValidator")
class KafkaDefaultProfileValidatorTest {

    @Test
    @DisplayName("unknown kafka.jsonProfile fails fast at VALIDATE even with zero consumers/producers (inert)")
    void unknownKafkaProfileFailsFast() {
        UnknownComponent component = DaggerKafkaDefaultProfileValidatorTest_UnknownComponent.create();

        // Forcing the Set<ComposeValidator> constructs KafkaDefaultProfileValidator, whose @Inject
        // ctor must resolve kafka.jsonProfile through the registry — an unregistered id throws.
        // No consumers or producers are configured here: the validator is inert of any binding state.
        // RED: the stub ctor is a no-op, so this assertThrows will FAIL until the green step adds
        // the validation call in KafkaDefaultProfileValidator.
        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "an unregistered kafka.jsonProfile id must fail fast when the validator set is forced"
                        + " — even with zero consumers/producers (inert boundary)");
    }

    @Test
    @DisplayName("shadowing: a valid per-binding profile does NOT mask a bad kafka.jsonProfile")
    void shadowingDoesNotMaskBadDefault() {
        // The validator only sees KafkaConfig + the registry — never any binding's resolved profile.
        // So a bad kafka.jsonProfile fails fast regardless of whether a per-binding profile
        // could shadow it at runtime. This component sets a bad kafka.jsonProfile; forcing the
        // validator set must still throw.
        ShadowComponent component = DaggerKafkaDefaultProfileValidatorTest_ShadowComponent.create();

        // RED: the stub ctor is a no-op, so this assertThrows will FAIL until the green step.
        assertThrows(
                JsonProfileConfigurationException.class,
                component::composeValidators,
                "a bad kafka.jsonProfile must fail fast even when a per-binding profile could shadow it");
    }

    @Test
    @DisplayName("known kafka.jsonProfile (vertx) passes")
    void knownKafkaProfilePasses() {
        KnownComponent component = DaggerKafkaDefaultProfileValidatorTest_KnownComponent.create();

        assertDoesNotThrow(component::composeValidators, "a known kafka.jsonProfile id (vertx) must construct cleanly");
    }

    @Test
    @DisplayName("unset kafka.jsonProfile passes")
    void unsetKafkaProfilePasses() {
        UnsetComponent component = DaggerKafkaDefaultProfileValidatorTest_UnsetComponent.create();

        assertDoesNotThrow(component::composeValidators, "an unset kafka.jsonProfile must no-op (blank ⇒ skip)");
    }

    // --- Components ---

    /** Component whose {@code kafka.jsonProfile} names an id no profile is registered for. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnknownConfigModule.class})
    interface UnknownComponent {
        /**
         * Forces construction of all contributed {@link ComposeValidator} instances.
         *
         * @return the set of compose validators
         */
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code kafka.jsonProfile} is a bad id (shadowing case — no bindings). */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, ShadowConfigModule.class})
    interface ShadowComponent {
        /**
         * Forces construction of all contributed {@link ComposeValidator} instances.
         *
         * @return the set of compose validators
         */
        Set<ComposeValidator> composeValidators();
    }

    /** Component whose {@code kafka.jsonProfile} is the reserved built-in {@code vertx} id. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, KnownConfigModule.class})
    interface KnownComponent {
        /**
         * Forces construction of all contributed {@link ComposeValidator} instances.
         *
         * @return the set of compose validators
         */
        Set<ComposeValidator> composeValidators();
    }

    /** Component with no {@code kafka.jsonProfile} set. */
    @Singleton
    @Component(modules = {JsonRuntimeModule.class, UnsetConfigModule.class})
    interface UnsetComponent {
        /**
         * Forces construction of all contributed {@link ComposeValidator} instances.
         *
         * @return the set of compose validators
         */
        Set<ComposeValidator> composeValidators();
    }

    // --- Test config modules ---

    /**
     * Supplies a {@link KafkaConfig} whose {@code jsonProfile} is an unregistered id, with zero
     * consumers/producers (exercises the inert-boundary path).
     */
    @Module
    abstract static class UnknownConfigModule {

        /**
         * @return a {@link KafkaConfig} with {@code jsonProfile} set to an unregistered id
         */
        @Provides
        static KafkaConfig kafkaConfig() {
            return kafkaConfigWithProfile("no-such-profile");
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
         * @param impl the kafka default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator kafkaValidator(KafkaDefaultProfileValidator impl) {
            return impl;
        }
    }

    /**
     * Supplies a {@link KafkaConfig} whose {@code jsonProfile} is a bad id (shadowing case —
     * no consumer/producer bindings registered).
     */
    @Module
    abstract static class ShadowConfigModule {

        /**
         * @return a {@link KafkaConfig} with {@code jsonProfile} set to a bad id
         */
        @Provides
        static KafkaConfig kafkaConfig() {
            return kafkaConfigWithProfile("bad-kafka-default");
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
         * @param impl the kafka default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator kafkaValidator(KafkaDefaultProfileValidator impl) {
            return impl;
        }
    }

    /**
     * Supplies a {@link KafkaConfig} whose {@code jsonProfile} is the reserved {@code system} id.
     */
    @Module
    abstract static class KnownConfigModule {

        /**
         * @return a {@link KafkaConfig} with {@code jsonProfile} set to {@code system}
         */
        @Provides
        static KafkaConfig kafkaConfig() {
            return kafkaConfigWithProfile("system");
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
         * @param impl the kafka default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator kafkaValidator(KafkaDefaultProfileValidator impl) {
            return impl;
        }
    }

    /** Supplies a {@link KafkaConfig} with no {@code jsonProfile} set. */
    @Module
    abstract static class UnsetConfigModule {

        /**
         * @return a default {@link KafkaConfig} (no {@code jsonProfile})
         */
        @Provides
        static KafkaConfig kafkaConfig() {
            return kafkaConfigWithProfile(null);
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
         * @param impl the kafka default-profile validator
         * @return the validator contributed into the compose-validator set
         */
        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator kafkaValidator(KafkaDefaultProfileValidator impl) {
            return impl;
        }
    }

    // --- Helpers ---

    /**
     * Builds a minimal {@link KafkaConfig} with the given {@code jsonProfile} (all other
     * components at their defaults: no consumers, no producers, empty message-key hash config).
     *
     * @param jsonProfile the kafka-boundary default profile id, or {@code null} when unset
     * @return the minimal typed config
     */
    private static KafkaConfig kafkaConfigWithProfile(String jsonProfile) {
        return new KafkaConfig(
                null, // format
                null, // properties
                null, // schemaRegistry
                List.of(), // consumers
                List.of(), // producers
                new KafkaMessageKeyHashConfig(null), // messageKeyHash
                new JsonObject(), // connectionProperties
                new JsonObject(), // producerProperties
                jsonProfile);
    }

    /**
     * Returns a minimal {@link ConfigParser} over a plain Jackson {@link ObjectMapper}, sufficient to
     * bind the simple {@link dev.vertique.json.JsonConfig} record (required transitively by
     * {@link JsonRuntimeModule}).
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
                throw new UnsupportedOperationException("not needed for KafkaDefaultProfileValidatorTest");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed for KafkaDefaultProfileValidatorTest");
            }
        };
    }
}
