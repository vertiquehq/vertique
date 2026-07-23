// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving {@link KafkaConsumerValidation#validateAndBuild} validates the
 * {@code jsonProfile} on <strong>both</strong> the enabled and disabled paths (fail-fast
 * parity, finding W-D).
 *
 * <p>An ENABLED consumer builds the deserializer via {@link KafkaSerdeRegistry#deserializer}, which
 * for the JSON format runs {@code JsonSerdeProvider.resolveMapper(serdeConfig)} and throws
 * {@link JsonProfileConfigurationException} for an unknown profile id. A DISABLED consumer is never
 * deployed, but it must reject the same misconfiguration at registration time rather than letting an
 * unknown profile id slip through until the consumer is later enabled.
 *
 * <p>kafka-core has no dependency on {@code vertique-kafka-json}, so a {@link ProfileAwareJsonProvider}
 * test double stands in for the real {@code JsonSerdeProvider}: its
 * {@link KafkaSerdeProvider#deserializer(Class, JsonObject)} throws
 * {@link JsonProfileConfigurationException} when the merged serde-config bag carries the
 * {@code unknown-profile} id, mirroring how the real provider validates the profile at
 * deserializer-build time.
 */
class KafkaConsumerValueJsonProfileValidationTest {

    /** An unknown JSON mapper profile id that {@link ProfileAwareJsonProvider} rejects. */
    private static final String UNKNOWN_PROFILE = "unknown-profile";

    /** A plain JSON payload type (no Avro auto-detect). */
    record PlainPayload(String data) {}

    /**
     * A JSON {@link KafkaSerdeProvider} test double that validates the {@code jsonProfile} at
     * deserializer-build time exactly as the real {@code JsonSerdeProvider} does: an unknown id
     * raises {@link JsonProfileConfigurationException}; an absent, blank, or known id builds a
     * working Jackson-free stub deserializer.
     */
    static final class ProfileAwareJsonProvider implements KafkaSerdeProvider {

        @Override
        public String format() {
            return "json";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return false;
        }

        @Override
        public boolean mayBlock() {
            return false;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            resolveProfile(endpointConfig);
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            // Mirror JsonSerdeProvider.resolveMapper: validate the profile at build time. An unknown
            // id throws JsonProfileConfigurationException (fail-fast at deserializer-build / deploy
            // time), not a per-record failure.
            resolveProfile(endpointConfig);
            return (data, topic, headers) -> null;
        }

        /**
         * Mirrors {@code JsonSerdeProvider.resolveMapper}: a missing, blank, or {@code vertx} profile
         * is the default path; the {@code unknown-profile} id is rejected with a
         * {@link JsonProfileConfigurationException}.
         *
         * @param endpointConfig the merged serde-config bag (read for {@code jsonProfile})
         */
        private static void resolveProfile(JsonObject endpointConfig) {
            String id = endpointConfig != null ? endpointConfig.getString("jsonProfile") : null;
            if (id == null || id.isBlank() || "vertx".equals(id)) {
                return;
            }
            if (UNKNOWN_PROFILE.equals(id)) {
                throw new JsonProfileConfigurationException(
                        "Unknown JSON profile id '" + id + "'. Known profiles: [vertx]");
            }
        }
    }

    /** Registry with the profile-aware JSON provider. */
    private static KafkaSerdeRegistry jsonRegistry() {
        return new KafkaSerdeRegistry(Set.of(new ProfileAwareJsonProvider()));
    }

    /**
     * Builds a {@link ResolvedKafkaConsumerConfig} for the named consumer, applying the per-consumer
     * {@code kafka.consumers.<name>} overrides (e.g. {@code enabled}, {@code jsonProfile}).
     *
     * @param name the consumer binding name
     * @param kafkaConfigJson the {@code kafka.*} config subtree
     * @return the resolved consumer config
     */
    private static ResolvedKafkaConsumerConfig config(String name, JsonObject kafkaConfigJson) {
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafkaConfigJson), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                "test.topic",
                "test-group",
                true,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                30_000L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    /**
     * Calls {@link KafkaConsumerValidation#validateAndBuild} for a BINDING kind with no custom
     * deserializer.
     *
     * @param name the consumer binding name
     * @param kafkaConfig the {@code kafka.*} config subtree
     * @param valueType the payload value type
     * @param registry the serde registry
     * @param violations mutable violations list
     * @return the built entry, or {@code null} if invalid
     */
    private static ConsumerEntry buildBinding(
            String name,
            JsonObject kafkaConfig,
            Class<?> valueType,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        return KafkaConsumerValidation.validateAndBuild(
                name,
                config(name, kafkaConfig),
                ConsumerEntry.Kind.BINDING,
                valueType,
                "test.address",
                null,
                false,
                List.of(),
                null,
                registry,
                null,
                null,
                new HashSet<>(),
                violations);
    }

    /**
     * Calls {@link KafkaConsumerValidation#validateAndBuild} for a ROUTER kind with the supplied
     * routes (no per-entry value type; the router resolves a single format across its routes).
     *
     * @param name the consumer binding name
     * @param kafkaConfig the {@code kafka.*} config subtree
     * @param routes the router's route entries
     * @param registry the serde registry
     * @param violations mutable violations list
     * @return the built entry, or {@code null} if invalid
     */
    private static ConsumerEntry buildRouter(
            String name,
            JsonObject kafkaConfig,
            List<ConsumerEntry.RouteEntry> routes,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        return KafkaConsumerValidation.validateAndBuild(
                name,
                config(name, kafkaConfig),
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                routes,
                null,
                registry,
                null,
                null,
                new HashSet<>(),
                violations);
    }

    /**
     * Builds a {@code kafka.consumers.<name>} subtree carrying the given enabled flag and profile id.
     *
     * @param name the consumer binding name
     * @param enabled whether the consumer is enabled
     * @param jsonProfile the profile id, or {@code null} to omit it
     * @return the {@code kafka.*} config subtree
     */
    private static JsonObject consumerConfig(String name, boolean enabled, String jsonProfile) {
        JsonObject consumer = new JsonObject().put("enabled", enabled);
        if (jsonProfile != null) {
            consumer.put("jsonProfile", jsonProfile);
        }
        return new JsonObject().put("consumers", new JsonObject().put(name, consumer));
    }

    @Test
    @DisplayName("disabled consumer with an unknown jsonProfile is rejected (fail-fast parity)")
    void disabledConsumerWithUnknownValueJsonProfileFailsValidation() {
        // Given: a DISABLED consumer whose jsonProfile is an unknown id.
        // When: validateAndBuild runs. Then: the entry is rejected (null) with a recorded violation
        // naming the format — the disabled path now validates the profile via a build-then-close
        // probe, instead of skipping it until the consumer is later enabled.
        List<String> violations = new ArrayList<>();
        ConsumerEntry entry = buildBinding(
                "off-bad-profile",
                consumerConfig("off-bad-profile", false, UNKNOWN_PROFILE),
                PlainPayload.class,
                jsonRegistry(),
                violations);

        assertNull(entry, "A disabled consumer with an unknown jsonProfile must be rejected (parity)");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("off-bad-profile")),
                "Expected a recorded violation for the unknown profile, got: " + violations);
    }

    @Test
    @DisplayName("enabled consumer with an unknown jsonProfile is rejected (regression guard)")
    void enabledConsumerWithUnknownValueJsonProfileFailsValidation() {
        // Given: an ENABLED consumer whose jsonProfile is an unknown id.
        // When: validateAndBuild builds the deserializer, which throws JsonProfileConfigurationException.
        // Then: that exception is caught and recorded as a violation (not allowed to escape the
        // collect-violations path) — the enabled-path catch must cover the profile-config exception.
        List<String> violations = new ArrayList<>();
        ConsumerEntry entry = buildBinding(
                "on-bad-profile",
                consumerConfig("on-bad-profile", true, UNKNOWN_PROFILE),
                PlainPayload.class,
                jsonRegistry(),
                violations);

        assertNull(entry, "An enabled consumer with an unknown jsonProfile must be rejected");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("on-bad-profile")),
                "Expected a recorded violation for the unknown profile, got: " + violations);
    }

    @Test
    @DisplayName("disabled consumer with a valid profile still passes validation and does not leak")
    void disabledConsumerWithValidProfileStillValidates() {
        // Given: a DISABLED consumer with a valid profile (vertx).
        // When: validateAndBuild runs the build-then-close probe.
        // Then: it passes with no violations, no escaped exception, and no registry-backed serde is
        // retained (a disabled consumer is never deployed).
        List<String> violations = new ArrayList<>();
        ConsumerEntry entry = buildBinding(
                "off-vertx",
                consumerConfig("off-vertx", false, "vertx"),
                PlainPayload.class,
                jsonRegistry(),
                violations);

        assertTrue(violations.isEmpty(), "Expected no violations for a valid disabled profile, got: " + violations);
        assertNotNull(entry, "A disabled consumer with a valid profile must still validate");
        assertFalse(entry.config().enabled());
        assertNull(entry.deserializer(), "A disabled consumer must not retain a registry-backed serde");
        assertFalse(entry.frameworkOwnedDeserializer());
    }

    @Test
    @DisplayName("ROUTER with an unknown jsonProfile fails fast at validation (parity with non-router lane)")
    void routerWithUnknownJsonProfile_failsFastAtValidation() {
        // Given: a ROUTER with a non-Void JSON payload route whose jsonProfile is an unknown id.
        // When: validateAndBuild runs router validation, which builds a PROBE deserializer for the
        // payload route via the registry (using the router's resolved serde-config bag carrying the
        // unknown jsonProfile id), and the JSON provider throws JsonProfileConfigurationException.
        // Then: the router is rejected (null) with a recorded violation at the VALIDATE phase — the
        // unknown profile is caught here, NOT deferred to deserializer construction at deploy/dispatch
        // (fail-fast parity with the non-router lane).
        List<String> violations = new ArrayList<>();
        ConsumerEntry entry = buildRouter(
                "router-bad-profile",
                consumerConfig("router-bad-profile", true, UNKNOWN_PROFILE),
                List.of(
                        new ConsumerEntry.RouteEntry(
                                "x-type", "", "plain", false, PlainPayload.class, "addr.plain", null, false),
                        new ConsumerEntry.RouteEntry("", "", "", true, Void.class, "addr.default", null, false)),
                jsonRegistry(),
                violations);

        assertNull(entry, "A router with an unknown jsonProfile must fail fast at validation, not defer to deploy");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("router-bad-profile")),
                "Expected a recorded violation for the unknown router profile, got: " + violations);
    }
}
