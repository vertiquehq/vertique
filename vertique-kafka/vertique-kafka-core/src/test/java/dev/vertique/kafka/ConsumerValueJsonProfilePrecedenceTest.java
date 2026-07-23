// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the consumer {@code jsonProfile} precedence (slice 4.2 + slice 2.4 extension):
 * config wins over the binding/listener default, which wins over the {@code kafka.jsonProfile}
 * boundary default, which wins over the {@code vertx} default. The resolved id is threaded into the
 * merged {@code serdeConfig} bag under the top-level {@code "jsonProfile"} key (present only when
 * non-null/non-blank), where {@link JsonSerdeProvider} reads it at deserializer-build time.
 *
 * <p>Slice 2.4 adds three new tests that prove the {@code kafka.jsonProfile} tier:
 * <ul>
 *   <li>{@code configOverBoundaryDefault} — per-consumer config beats the boundary default;</li>
 *   <li>{@code listenerDefaultOverBoundaryDefault} — listener default beats the boundary default;</li>
 *   <li>{@code kafkaBoundaryApplies} — boundary default applies when no per-consumer/listener default;</li>
 *   <li>{@code vertxFloor} — nothing set leaves the bag key absent (FR-JSON-057).</li>
 * </ul>
 */
@DisplayName("Consumer jsonProfile precedence")
class ConsumerValueJsonProfilePrecedenceTest {

    /**
     * Resolves a consumer config from the raw {@code kafka} section, parsing it into the typed
     * {@link KafkaConfig} (and looking up the per-consumer config by name) exactly as the runtime
     * boundary does, threading {@code defaultJsonProfile} as the binding/listener default.
     *
     * @param name the consumer name
     * @param defaultJsonProfile the binding/listener default profile id (may be {@code null})
     * @param kafkaConfigJson the raw {@code kafka} config section
     * @return the resolved consumer config
     */
    private static ResolvedKafkaConsumerConfig resolve(
            String name, String defaultJsonProfile, JsonObject kafkaConfigJson) {
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafkaConfigJson), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                "the.topic",
                "the-group",
                true,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                30_000L,
                defaultJsonProfile,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    private static JsonObject consumerWithProfile(String name, String profile) {
        return new JsonObject()
                .put("consumers", new JsonObject().put(name, new JsonObject().put("jsonProfile", profile)));
    }

    // --- Pre-existing (slice 4.2) ---

    @Test
    @DisplayName("config jsonProfile 'A' wins over binding/listener default 'B'")
    void configWinsOverDefault() {
        ResolvedKafkaConsumerConfig config = resolve("c", "B", consumerWithProfile("c", "A"));
        assertEquals("A", config.serdeConfig().getString("jsonProfile"));
    }

    @Test
    @DisplayName("binding/listener default 'B' applies when config has no jsonProfile")
    void defaultAppliesWhenConfigAbsent() {
        ResolvedKafkaConsumerConfig config = resolve("c", "B", new JsonObject());
        assertEquals("B", config.serdeConfig().getString("jsonProfile"));
    }

    @Test
    @DisplayName("no profile anywhere leaves the bag key absent (vertx default)")
    void neitherLeavesKeyAbsent() {
        ResolvedKafkaConsumerConfig config = resolve("c", null, new JsonObject());
        assertNull(config.serdeConfig().getString("jsonProfile"));
    }

    // --- Slice 2.4: kafka.jsonProfile boundary tier ---

    @Test
    @DisplayName("configOverBoundaryDefault: consumer config 'a' wins over kafka.jsonProfile 'b'")
    void configOverBoundaryDefault() {
        // Given: per-consumer jsonProfile="a" and kafka.jsonProfile="b"
        JsonObject kafkaJson = new JsonObject()
                .put("jsonProfile", "b")
                .put("consumers", new JsonObject().put("c", new JsonObject().put("jsonProfile", "a")));

        // When: resolved with no listener default
        ResolvedKafkaConsumerConfig config = resolve("c", null, kafkaJson);

        // Then: the per-consumer config wins (most specific tier)
        // RED: KafkaConfig does not yet have jsonProfile, so this test will fail
        // until the neutral field is added AND the chain is extended.
        assertEquals(
                "a",
                config.serdeConfig().getString("jsonProfile"),
                "per-consumer config must win over kafka.jsonProfile boundary default");
    }

    @Test
    @DisplayName("listenerDefaultOverBoundaryDefault: @KafkaListener default 'a' wins over kafka.jsonProfile 'b'")
    void listenerDefaultOverBoundaryDefault() {
        // Given: kafka.jsonProfile="b" but no per-consumer config; listener default="a"
        JsonObject kafkaJson = new JsonObject().put("jsonProfile", "b");

        // When: resolved with listener default "a"
        ResolvedKafkaConsumerConfig config = resolve("c", "a", kafkaJson);

        // Then: the listener default wins over the kafka boundary default
        // RED: KafkaConfig.jsonProfile field does not exist yet.
        assertEquals(
                "a",
                config.serdeConfig().getString("jsonProfile"),
                "@KafkaListener default must win over kafka.jsonProfile boundary default");
    }

    @Test
    @DisplayName("kafkaBoundaryApplies: kafka.jsonProfile 'b' is used when no per-consumer or listener default")
    void kafkaBoundaryApplies() {
        // Given: only kafka.jsonProfile="b"; no per-consumer config, no listener default
        JsonObject kafkaJson = new JsonObject().put("jsonProfile", "b");

        // When: resolved with no listener default
        ResolvedKafkaConsumerConfig config = resolve("c", null, kafkaJson);

        // Then: the kafka boundary default reaches the serde bag
        // RED: KafkaConfig.jsonProfile field does not exist yet AND the chain is not extended,
        // so the serde bag will NOT carry "b" — this assertion will fail red.
        assertEquals(
                "b",
                config.serdeConfig().getString("jsonProfile"),
                "kafka.jsonProfile boundary default must reach the serde bag when no higher tier is set");
    }

    @Test
    @DisplayName("vertxFloor: nothing set leaves the bag key absent (FR-JSON-057)")
    void vertxFloor() {
        // Given: no kafka.jsonProfile, no per-consumer, no listener default
        ResolvedKafkaConsumerConfig config = resolve("c", null, new JsonObject());

        // Then: the vertx default path (no key in bag)
        assertNull(config.serdeConfig().getString("jsonProfile"));
        assertFalse(
                config.serdeConfig().containsKey("jsonProfile"),
                "the vertx floor must leave the jsonProfile bag key absent");
    }
}
