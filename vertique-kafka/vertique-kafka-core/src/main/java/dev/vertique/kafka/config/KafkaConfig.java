// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed model of the {@code kafka} configuration section.
 *
 * <p>This is the typed, validated result assembled at the Dagger provider boundary (see
 * {@code KafkaModule}); module internals depend on it (or on the {@link #consumerIndex()}/
 * {@link #producerIndex()} indexes), never on the raw {@link JsonObject}. Consumers and producers are
 * keyed objects ({@code kafka.consumers.{name}}, {@code kafka.producers.{name}}); each key is injected
 * into the element record's identity field via {@link KeyedBy @KeyedBy("name")}. Per-method producer
 * config nests one level deeper under {@code kafka.producers.{name}.methods.{method}}.
 *
 * <p>The {@code properties} and {@code schemaRegistry} blocks are intentionally open property bags
 * (config rule R9) whose values are kept intact for runtime use; secret keys in every open bag —
 * {@code properties}, {@code schemaRegistry}, {@code connectionProperties}, {@code producerProperties}
 * — are masked at every depth only when this record is rendered to a string (see {@link #toString()}).
 *
 * <p>Two of the components are <strong>not</strong> parsed by Jackson from the structured section:
 * {@link #connectionProperties()} and {@link #producerProperties()} are open Kafka-client bags that
 * sit at the {@code kafka} root <em>outside</em> the structured keys, and so cannot be captured by
 * {@link ConfigParser#parse}. The boundary factory {@link #fromConfig(JsonObject)} parses the
 * structured part and then attaches these two bags via {@link #withClientProperties}:
 * <ul>
 *   <li>{@code connectionProperties} — every <em>root</em> {@code kafka.*} key that is not a known
 *       structured key ({@code format}, {@code properties}, {@code schemaRegistry}, {@code consumers},
 *       {@code producers}, {@code producer}, {@code messageKeyHash}). These are the loose Kafka
 *       client-connection scalars — {@code bootstrap.servers}, {@code security.protocol}, {@code
 *       sasl.*} — accepted in both flat-dotted and nested forms (the same {@code KafkaConfigHelper}
 *       flatten/resolve logic the runtime has always used).</li>
 *   <li>{@code producerProperties} — the global producer bag at {@code kafka.producer.properties}
 *       (singular {@code producer}), applied to every producer client below {@code connectionProperties}
 *       and {@code properties} and above the per-producer overrides.</li>
 * </ul>
 *
 * @param format the top-level default value format (e.g. {@code "avro"}), or {@code null} when not set
 * @param properties the open top-level Kafka native property bag, or {@code null} when none
 * @param schemaRegistry the open schema-registry block, or {@code null} when none
 * @param consumers the configured consumers; identity per element is {@code name} (default empty)
 * @param producers the configured producers; identity per element is {@code name} (default empty)
 * @param messageKeyHash the Kafka message-key hash configuration (defaults applied; never {@code
 *     null} after construction)
 * @param connectionProperties the loose top-level Kafka client-connection keys captured from the
 *     {@code kafka} root (never {@code null} after construction; empty when none); both flat-dotted
 *     and nested forms are preserved verbatim
 * @param producerProperties the global producer property bag from {@code kafka.producer.properties}
 *     (never {@code null} after construction; empty when none)
 * @param jsonProfile the kafka-boundary-level default JSON mapper profile id (key
 *     {@code kafka.jsonProfile}), or {@code null}/blank when not set — treated as "no boundary
 *     default" by the consumer/producer resolution chains (the {@code json.jsonProfile} global default
 *     and ultimately the {@code vertx} floor are consulted in kafka-json). This field is format-agnostic:
 *     it carries only a neutral string; the kafka-json module applies it when selecting the
 *     {@code ObjectMapper} for JSON endpoints.
 */
public record KafkaConfig(
        String format,
        JsonObject properties,
        JsonObject schemaRegistry,
        @KeyedBy("name") List<KafkaConsumerConfig> consumers,
        @KeyedBy("name") List<KafkaProducerConfig> producers,
        KafkaMessageKeyHashConfig messageKeyHash,
        JsonObject connectionProperties,
        JsonObject producerProperties,
        @Nullable String jsonProfile) {

    /**
     * The root {@code kafka.*} keys that {@link ConfigParser} parses into the structured components of
     * this record. Every other root key is a loose Kafka client-connection scalar captured into
     * {@link #connectionProperties()}. The {@code "jsonProfile"} key is included here so the
     * kafka-boundary default profile id is not mistakenly routed into {@code connectionProperties}.
     */
    private static final Set<String> STRUCTURED_ROOT_KEYS = Set.of(
            "format",
            "properties",
            "schemaRegistry",
            "consumers",
            "producers",
            "producer",
            "messageKeyHash",
            "jsonProfile");

    /** Retired structured keys that are ignored rather than forwarded to Kafka clients. */
    private static final Set<String> RETIRED_ROOT_KEYS = Set.of("audit");

    /**
     * Compact constructor copying the keyed-collection lists defensively for immutability and
     * normalizing the two client-property bags to non-{@code null} empty objects. The
     * {@code jsonProfile} component is passed through unchanged (nullable string).
     *
     * @param format the top-level format (nullable)
     * @param properties the open top-level property bag (nullable)
     * @param schemaRegistry the open schema-registry block (nullable)
     * @param consumers the consumer list (defensively copied; {@code null} becomes empty)
     * @param producers the producer list (defensively copied; {@code null} becomes empty)
     * @param messageKeyHash the message-key hash config (nullable; never null after the {@code
     *     @JsonCreator} factory)
     * @param connectionProperties the loose top-level connection keys ({@code null} becomes empty)
     * @param producerProperties the global producer bag ({@code null} becomes empty)
     * @param jsonProfile the kafka-boundary default profile id (nullable, passed through)
     */
    public KafkaConfig {
        consumers = consumers != null ? List.copyOf(consumers) : List.of();
        producers = producers != null ? List.copyOf(producers) : List.of();
        connectionProperties = connectionProperties != null ? connectionProperties : new JsonObject();
        producerProperties = producerProperties != null ? producerProperties : new JsonObject();
    }

    /**
     * Jackson factory for the <em>structured</em> part of the section: {@code consumers}/{@code producers}
     * default to empty and {@code messageKeyHash} defaults to an empty {@link
     * KafkaMessageKeyHashConfig} (no secret) when absent. The two client-property bags ({@code
     * connectionProperties}, {@code producerProperties}) are never read by Jackson — they live at the
     * {@code kafka} root outside the structured keys and are attached afterwards by {@link #fromConfig}
     * via {@link #withClientProperties}, so this factory seeds them empty. The {@code jsonProfile} key
     * IS at the {@code kafka} root and IS parsed by Jackson from the structured section.
     *
     * @param format the top-level format; passed through (nullable)
     * @param properties the open top-level property bag; passed through (nullable)
     * @param schemaRegistry the open schema-registry block; passed through (nullable)
     * @param consumers the consumer list; defaults to empty when {@code null}
     * @param producers the producer list; defaults to empty when {@code null}
     * @param messageKeyHash the message-key hash config; defaults to a no-secret {@link
     *     KafkaMessageKeyHashConfig} when {@code null}
     * @param jsonProfile the kafka-boundary default JSON mapper profile id; passed through (nullable)
     * @return the deserialized config with defaults applied and empty client-property bags
     */
    @JsonCreator
    static KafkaConfig fromJson(
            @JsonProperty("format") @Nullable String format,
            @JsonProperty("properties") @Nullable JsonObject properties,
            @JsonProperty("schemaRegistry") @Nullable JsonObject schemaRegistry,
            @JsonProperty("consumers") @Nullable List<KafkaConsumerConfig> consumers,
            @JsonProperty("producers") @Nullable List<KafkaProducerConfig> producers,
            @JsonProperty("messageKeyHash") @Nullable KafkaMessageKeyHashConfig messageKeyHash,
            @JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new KafkaConfig(
                format,
                properties,
                schemaRegistry,
                consumers != null ? consumers : List.of(),
                producers != null ? producers : List.of(),
                messageKeyHash != null ? messageKeyHash : new KafkaMessageKeyHashConfig(null),
                new JsonObject(),
                new JsonObject(),
                jsonProfile);
    }

    /**
     * Returns a copy of this config with the two boundary-extracted client-property bags attached. The
     * structured components (including {@link #jsonProfile()}) are carried over unchanged; only
     * {@link #connectionProperties()} and {@link #producerProperties()} are replaced.
     *
     * @param connectionProperties the loose top-level connection keys (may be {@code null})
     * @param producerProperties the global producer bag (may be {@code null})
     * @return a copy carrying the supplied client-property bags
     */
    public KafkaConfig withClientProperties(JsonObject connectionProperties, JsonObject producerProperties) {
        return new KafkaConfig(
                format,
                properties,
                schemaRegistry,
                consumers,
                producers,
                messageKeyHash,
                connectionProperties,
                producerProperties,
                jsonProfile);
    }

    /**
     * Parses the {@code kafka} section of a root config object into a typed {@link KafkaConfig} via the
     * shared {@link ConfigParser} (which injects each {@code consumers}/{@code producers} key into the
     * element identity field), then attaches the two client-property bags that live at the {@code kafka}
     * root outside the structured keys:
     * <ul>
     *   <li>{@code connectionProperties} — every root key not in {@link #STRUCTURED_ROOT_KEYS} (the loose
     *       {@code bootstrap.servers}/{@code security.protocol}/{@code sasl.*} connection scalars, in
     *       either flat-dotted or nested form);</li>
     *   <li>{@code producerProperties} — {@code kafka.producer.properties} (singular {@code producer}).</li>
     * </ul>
     *
     * @param rootConfig the root application config, qualified {@code @VertxConfig} at the boundary
     * @param parser the injected config parser
     * @return the parsed, validated Kafka config with client-property bags attached
     */
    public static KafkaConfig fromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject kafkaSection = JsonConfigPaths.navigateObject(rootConfig, "kafka");
        KafkaConfig structured = parser.parse(kafkaSection, KafkaConfig.class);
        JsonObject connectionProperties = extractConnectionProperties(kafkaSection);
        JsonObject producerProperties = JsonConfigPaths.navigateObject(kafkaSection, "producer", "properties");
        return structured.withClientProperties(connectionProperties, producerProperties);
    }

    /**
     * Extracts the loose top-level Kafka client-connection keys from the raw {@code kafka} section:
     * every root key that is not one of the {@link #STRUCTURED_ROOT_KEYS}. The values are copied
     * verbatim, preserving both the flat-dotted ({@code "bootstrap.servers"}) and nested
     * ({@code {"bootstrap":{"servers":...}}}) forms that the runtime's {@code KafkaConfigHelper}
     * resolution accepts.
     *
     * @param kafkaSection the raw {@code kafka} section (never {@code null})
     * @return a new {@link JsonObject} of the loose connection keys (possibly empty)
     */
    private static JsonObject extractConnectionProperties(JsonObject kafkaSection) {
        JsonObject connection = new JsonObject();
        for (String key : kafkaSection.fieldNames()) {
            if (!STRUCTURED_ROOT_KEYS.contains(key) && !RETIRED_ROOT_KEYS.contains(key)) {
                connection.put(key, kafkaSection.getValue(key));
            }
        }
        return connection;
    }

    /**
     * Builds an immutable {@code name -> KafkaConsumerConfig} lookup index over {@link #consumers()}.
     *
     * <p>Built after the per-record validation already performed at parse time; insertion order is
     * preserved.
     *
     * @return an immutable index keyed by consumer name
     */
    public Map<String, KafkaConsumerConfig> consumerIndex() {
        Map<String, KafkaConsumerConfig> index = new LinkedHashMap<>();
        for (KafkaConsumerConfig consumer : consumers) {
            index.put(consumer.name(), consumer);
        }
        return Map.copyOf(index);
    }

    /**
     * Builds an immutable {@code name -> KafkaProducerConfig} lookup index over {@link #producers()}.
     *
     * <p>Built after the per-record validation already performed at parse time; insertion order is
     * preserved.
     *
     * @return an immutable index keyed by producer name
     */
    public Map<String, KafkaProducerConfig> producerIndex() {
        Map<String, KafkaProducerConfig> index = new LinkedHashMap<>();
        for (KafkaProducerConfig producer : producers) {
            index.put(producer.name(), producer);
        }
        return Map.copyOf(index);
    }

    /**
     * Renders this config with secret keys in every open Kafka bag masked, so a log line or exception
     * message never reveals a credential. {@code properties}, {@code schemaRegistry},
     * {@code connectionProperties}, and {@code producerProperties} are all routed through the recursive
     * {@link KafkaSecretKeys#scrubToString(JsonObject)} (masking secret keys at every depth): the
     * connection and producer bags may carry {@code sasl.*} secrets, and {@code schemaRegistry} is an
     * open registry bag that can carry Confluent auth credentials (e.g. {@code schema.registry.ssl.*}
     * passwords), so it is scrubbed rather than rendered verbatim.
     *
     * @return a log-safe string rendering of this config
     */
    @Override
    public String toString() {
        return "KafkaConfig[format=" + format + ", properties=" + KafkaSecretKeys.scrubToString(properties)
                + ", schemaRegistry=" + KafkaSecretKeys.scrubToString(schemaRegistry) + ", consumers=" + consumers
                + ", producers=" + producers + ", messageKeyHash=" + messageKeyHash + ", connectionProperties="
                + KafkaSecretKeys.scrubToString(connectionProperties) + ", producerProperties="
                + KafkaSecretKeys.scrubToString(producerProperties) + ", jsonProfile=" + jsonProfile + "]";
    }
}
