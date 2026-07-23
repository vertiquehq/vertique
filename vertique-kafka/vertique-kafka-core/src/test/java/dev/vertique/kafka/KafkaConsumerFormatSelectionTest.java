// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for format-aware consumer deserializer selection in
 * {@link KafkaConsumerValidation#validateAndBuild}.
 *
 * <p>Covers format resolution for Models 1, 2, and 4 (BINDING/HANDLER kinds), threading
 * enforcement for {@code mayBlock} deserializers, validation rules around custom deserializers
 * (Model 2), and provider-required validation: the json provider (via {@link TestJsonSerdeProvider})
 * is now required for any consumer that resolves to the "json" format (no built-in fallback).
 *
 * <p>A {@link FakeAvroProvider} stands in for a real Avro format provider; it auto-detects
 * {@link FakeRecord} types and reports {@code mayBlock=true}.
 */
class KafkaConsumerFormatSelectionTest {

    // --- Test doubles ---

    /** Marker interface auto-detectable by {@link FakeAvroProvider}. */
    interface FakeAvroRecord {}

    /** A payload type that implements {@link FakeAvroRecord} (auto-detected as "avro"). */
    record FakeRecord(String field) implements FakeAvroRecord {}

    /** A plain JSON payload type that does NOT implement {@link FakeAvroRecord}. */
    record PlainPayload(String data) {}

    /**
     * Fake "avro" format provider that auto-detects {@link FakeAvroRecord} types and
     * reports {@code mayBlock=true} (simulating a Schema Registry HTTP call).
     */
    static final class FakeAvroProvider implements KafkaSerdeProvider {

        /** Set when a deserializer built by this provider is closed (to prove rejected-entry cleanup). */
        final java.util.concurrent.atomic.AtomicBoolean deserializerClosed =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeAvroRecord.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> ("FAKE:" + value).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            return new KafkaDeserializer<>() {
                @Override
                public V deserialize(byte[] data, String topic, Map<String, String> headers) {
                    return (V) new FakeRecord(new String(data, StandardCharsets.UTF_8));
                }

                @Override
                public void close() {
                    deserializerClosed.set(true);
                }
            };
        }
    }

    /**
     * Fake "avro" provider that only {@link #supports(Class)} {@link FakeAvroRecord} types (mirroring
     * Apicurio's SpecificRecord restriction), so a test can prove the disabled path enforces provider
     * type support — not just provider existence — consistently with routers and the enabled path.
     * Its {@link #deserializer(Class, JsonObject)} rejects an unsupported type at build time (as a real
     * provider does), so the build-then-close validation probe surfaces the unsupported type as a
     * violation.
     */
    static final class TypeRestrictedAvroProvider implements KafkaSerdeProvider {
        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeAvroRecord.class.isAssignableFrom(type);
        }

        @Override
        public boolean supports(Class<?> type) {
            return FakeAvroRecord.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            // Reject an unsupported type at deserializer-build time, mirroring a real Avro provider
            // (Apicurio rejects a non-SpecificRecord type). The build-then-close validation probe then
            // surfaces this as a recorded violation on both the enabled and disabled paths.
            if (!supports(type)) {
                throw new IllegalArgumentException(
                        "value type " + type.getName() + " is not a " + FakeAvroRecord.class.getSimpleName());
            }
            return (data, topic, headers) -> null;
        }
    }

    /**
     * A second provider that also auto-detects {@link FakeAvroRecord} but with a different format,
     * used to exercise the ambiguous-auto-detect path (two providers claim the same type).
     */
    static final class SecondAvroLikeProvider implements KafkaSerdeProvider {
        @Override
        public String format() {
            return "proto";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return FakeAvroRecord.class.isAssignableFrom(type);
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            return (data, topic, headers) -> null;
        }
    }

    /** Registry with the Avro fake provider and the JSON provider. */
    private static KafkaSerdeRegistry avroRegistry() {
        return new KafkaSerdeRegistry(Set.of(new FakeAvroProvider(), new TestJsonSerdeProvider()));
    }

    /** Registry where two providers both auto-detect {@link FakeAvroRecord} (ambiguous). */
    private static KafkaSerdeRegistry ambiguousRegistry() {
        return new KafkaSerdeRegistry(
                Set.of(new FakeAvroProvider(), new SecondAvroLikeProvider(), new TestJsonSerdeProvider()));
    }

    /** Registry with only the JSON provider (no Avro). */
    private static KafkaSerdeRegistry jsonRegistry() {
        return new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));
    }

    /** Registry with no providers — resolving any format will fail. */
    private static KafkaSerdeRegistry emptyRegistry() {
        return new KafkaSerdeRegistry(Set.of());
    }

    /**
     * Builds a minimal {@link ResolvedKafkaConsumerConfig} using the given kafkaConfig tree.
     *
     * @param name consumer binding name
     * @param kafkaConfig the {@code kafka.*} config subtree (may include "format", "schemaRegistry",
     *     and per-consumer overrides)
     * @return resolved config
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
     * @param name consumer binding name
     * @param kafkaConfig the full {@code kafka.*} config subtree
     * @param valueType the payload value type (may be null)
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
        ResolvedKafkaConsumerConfig cfg = config(name, kafkaConfig);
        return KafkaConsumerValidation.validateAndBuild(
                name,
                cfg,
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
     * Calls {@link KafkaConsumerValidation#validateAndBuild} for a HANDLER kind.
     *
     * @param name consumer binding name
     * @param kafkaConfig the full {@code kafka.*} config subtree
     * @param valueType the payload value type
     * @param registry the serde registry
     * @param violations mutable violations list
     * @return the built entry, or {@code null} if invalid
     */
    private static ConsumerEntry buildHandler(
            String name,
            JsonObject kafkaConfig,
            Class<?> valueType,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        ResolvedKafkaConsumerConfig cfg = config(name, kafkaConfig);
        KafkaRecordHandler<?> stubHandler = message -> io.vertx.core.Future.succeededFuture();
        return KafkaConsumerValidation.validateAndBuild(
                name,
                cfg,
                ConsumerEntry.Kind.HANDLER,
                valueType,
                null,
                null,
                false,
                List.of(),
                stubHandler,
                registry,
                null,
                null,
                new HashSet<>(),
                violations);
    }

    /**
     * Calls {@link KafkaConsumerValidation#validateAndBuild} for a BINDING kind with a custom
     * deserializer (Model 2).
     *
     * @param name consumer binding name
     * @param kafkaConfig the full {@code kafka.*} config subtree
     * @param valueType the payload value type
     * @param customDeserializer the caller-supplied custom deserializer
     * @param registry the serde registry
     * @param violations mutable violations list
     * @return the built entry, or {@code null} if invalid
     */
    private static ConsumerEntry buildBindingWithCustomDeser(
            String name,
            JsonObject kafkaConfig,
            Class<?> valueType,
            KafkaDeserializer<?> customDeserializer,
            KafkaSerdeRegistry registry,
            List<String> violations) {
        ResolvedKafkaConsumerConfig cfg = config(name, kafkaConfig);
        return KafkaConsumerValidation.validateAndBuild(
                name,
                cfg,
                ConsumerEntry.Kind.BINDING,
                valueType,
                "test.address",
                null,
                false,
                List.of(),
                null,
                registry,
                customDeserializer,
                null,
                new HashSet<>(),
                violations);
    }

    // ===========================================================================================
    // Model 1/4: auto-detected format
    // ===========================================================================================

    @Nested
    @DisplayName("Model 1/4 BINDING/HANDLER: auto-detected format (avro provider present)")
    class AutoDetectedFormat {

        @Test
        @DisplayName("FakeRecord payload auto-detects to avro, deserializer.mayBlock=true, threading=WORKER")
        void avroAutoDetectForcesWorker() {
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("auto-avro", new JsonObject(), FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("avro", entry.valueFormat(), "Expected avro format");
            assertNotNull(entry.deserializer(), "Expected a deserializer");
            assertTrue(entry.deserializer().mayBlock(), "Avro deserializer must mayBlock=true");
            assertEquals(
                    ThreadingModel.WORKER,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "mayBlock must force WORKER threading");
        }

        @Test
        @DisplayName("Model 4 HANDLER with FakeRecord also auto-detects avro and forces WORKER")
        void handlerAvroAutoDetectForcesWorker() {
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildHandler("auto-avro-handler", new JsonObject(), FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("avro", entry.valueFormat());
            assertTrue(entry.deserializer().mayBlock());
            assertEquals(
                    ThreadingModel.WORKER, entry.config().deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("global kafka.format=json overrides auto-detect, threading stays EVENT_LOOP")
        void globalJsonOverridesAutoDetect() {
            JsonObject kafkaConfig = new JsonObject().put("format", "json");
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("global-json", kafkaConfig, FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat(), "Global json must override auto-detect");
            assertNotNull(entry.deserializer());
            // JSON deserializer never blocks
            // threading must be EVENT_LOOP (default), not WORKER
            assertEquals(
                    ThreadingModel.EVENT_LOOP,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "Non-blocking format must not force WORKER");
        }

        @Test
        @DisplayName("endpoint-level format=json overrides auto-detect, threading stays EVENT_LOOP")
        void endpointJsonOverridesAutoDetect() {
            // kafka.consumers.ep-json.format=json
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("ep-json", new JsonObject().put("format", "json")));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = buildBinding("ep-json", kafkaConfig, FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat(), "Endpoint json must override auto-detect");
            assertEquals(
                    ThreadingModel.EVENT_LOOP,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "Non-blocking format must not force WORKER");
        }
    }

    // ===========================================================================================
    // Threading enforcement
    // ===========================================================================================

    @Nested
    @DisplayName("Threading enforcement (NFR-AVRO-005)")
    class ThreadingEnforcement {

        @Test
        @DisplayName("mayBlock + explicit worker=false → violation, entry is null")
        void mayBlockWithWorkerFalseIsViolation() {
            // kafka.consumers.blocked-false.worker=false
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("blocked-false", new JsonObject().put("worker", false)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("blocked-false", kafkaConfig, FakeRecord.class, avroRegistry(), violations);

            assertNull(entry, "Entry must be null when worker=false conflicts with mayBlock");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("worker=false")),
                    "Expected worker=false violation, got: " + violations);
        }

        @Test
        @DisplayName("a registry deserializer built before a late rejection (worker=false) is closed, not leaked")
        void rejectedEntryClosesRegistryDeserializer() {
            FakeAvroProvider provider = new FakeAvroProvider();
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(provider, new TestJsonSerdeProvider()));
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("blocked-false", new JsonObject().put("worker", false)));
            List<String> violations = new ArrayList<>();

            ConsumerEntry entry = buildBinding("blocked-false", kafkaConfig, FakeRecord.class, registry, violations);

            assertNull(entry, "Entry must be rejected (worker=false vs mayBlock)");
            assertTrue(
                    provider.deserializerClosed.get(),
                    "The registry-built deserializer must be closed when the entry is rejected after build");
        }

        @Test
        @DisplayName("a disabled avro consumer validates via a build-then-close probe and retains no serde")
        void disabledConsumerSkipsSerdeAllocation() {
            FakeAvroProvider provider = new FakeAvroProvider();
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(provider, new TestJsonSerdeProvider()));
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("off", new JsonObject().put("enabled", false)));
            List<String> violations = new ArrayList<>();

            ConsumerEntry entry = buildBinding("off", kafkaConfig, FakeRecord.class, registry, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertFalse(entry.config().enabled());
            assertEquals("avro", entry.valueFormat(), "format is still resolved for diagnostics");
            assertNull(entry.deserializer(), "a disabled consumer must not RETAIN a registry-backed serde");
            assertFalse(entry.frameworkOwnedDeserializer());
            // The disabled path now validates the full config (incl. jsonProfile) by building a
            // probe deserializer and closing it immediately — the probe is built then closed, never
            // retained on the entry.
            assertTrue(
                    provider.deserializerClosed.get(),
                    "the disabled-path validation probe must be built then closed, not leaked");
        }

        @Test
        @DisplayName("a disabled consumer is still rejected for worker=false against a blocking format")
        void disabledConsumerStillRejectsWorkerFalseForBlockingFormat() {
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put(
                                            "off-blocked",
                                            new JsonObject()
                                                    .put("enabled", false)
                                                    .put("worker", false)));
            List<String> violations = new ArrayList<>();

            ConsumerEntry entry =
                    buildBinding("off-blocked", kafkaConfig, FakeRecord.class, avroRegistry(), violations);

            assertNull(entry, "A disabled mayBlock consumer with worker=false must still be rejected (parity)");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("worker=false")),
                    "Expected worker=false violation, got: " + violations);
        }

        @Test
        @DisplayName("a disabled consumer is still rejected when the provider does not support the value type")
        void disabledConsumerStillRejectsUnsupportedType() {
            KafkaSerdeRegistry registry =
                    new KafkaSerdeRegistry(Set.of(new TypeRestrictedAvroProvider(), new TestJsonSerdeProvider()));
            // explicit format=avro so it resolves regardless of auto-detect; PlainPayload is not supported.
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject()
                                    .put(
                                            "off-bad-type",
                                            new JsonObject()
                                                    .put("enabled", false)
                                                    .put("format", "avro")));
            List<String> violations = new ArrayList<>();

            ConsumerEntry entry = buildBinding("off-bad-type", kafkaConfig, PlainPayload.class, registry, violations);

            assertNull(entry, "A disabled consumer with an unsupported value type must be rejected (parity)");
            // The build-then-close validation probe surfaces the unsupported type via the provider's
            // deserializer-build rejection, recorded under the consumer-named "requests value format"
            // violation (same path the enabled consumer uses).
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("off-bad-type") && v.contains("avro")),
                    "Expected an unsupported-type build violation, got: " + violations);
        }

        @Test
        @DisplayName("a disabled json consumer is still rejected when no json provider is registered")
        void disabledJsonConsumerRequiresJsonProvider() {
            // No providers at all — even the json format requires a registered provider now
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("off-json", new JsonObject().put("enabled", false)));
            List<String> violations = new ArrayList<>();

            ConsumerEntry entry =
                    buildBinding("off-json", kafkaConfig, PlainPayload.class, emptyRegistry(), violations);

            assertNull(entry, "A disabled json consumer with no json provider must be rejected");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("json")),
                    "Expected a violation about the missing json provider, got: " + violations);
        }

        @Test
        @DisplayName("mayBlock + absent worker config → forced to WORKER, no violation")
        void mayBlockWithAbsentWorkerForcesWorker() {
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("forced-worker", new JsonObject(), FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals(
                    ThreadingModel.WORKER, entry.config().deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("mayBlock + explicit worker=true → WORKER, no violation")
        void mayBlockWithWorkerTrueIsOk() {
            // kafka.consumers.explicit-worker.worker=true
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("explicit-worker", new JsonObject().put("worker", true)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("explicit-worker", kafkaConfig, FakeRecord.class, avroRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals(
                    ThreadingModel.WORKER, entry.config().deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("non-blocking JSON + explicit worker=true → WORKER honored, no violation")
        void nonBlockingWithWorkerTrueIsHonored() {
            // kafka.consumers.json-worker.worker=true
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("json-worker", new JsonObject().put("worker", true)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("json-worker", kafkaConfig, PlainPayload.class, jsonRegistry(), violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat());
            assertEquals(
                    ThreadingModel.WORKER, entry.config().deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("a fake json provider with mayBlock=true forces WORKER threading on a binding")
        void jsonProviderWithMayBlockTrueForceWorker() {
            // Simulate a hypothetical blocking json provider
            KafkaSerdeProvider blockingJsonProvider = new KafkaSerdeProvider() {
                @Override
                public String format() {
                    return "json";
                }

                @Override
                public boolean mayBlock() {
                    return true;
                }

                @Override
                public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                    return (value, topic, headers) -> new byte[0];
                }

                @Override
                public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                    return (data, topic, headers) -> null;
                }
            };
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(blockingJsonProvider));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("blocking-json", new JsonObject(), PlainPayload.class, registry, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat());
            assertEquals(
                    ThreadingModel.WORKER,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "A mayBlock json provider must force WORKER threading");
        }

        @Test
        @DisplayName("a fake json provider with mayBlock=true and worker=false is rejected")
        void jsonProviderWithMayBlockTrueAndWorkerFalseIsViolation() {
            KafkaSerdeProvider blockingJsonProvider = new KafkaSerdeProvider() {
                @Override
                public String format() {
                    return "json";
                }

                @Override
                public boolean mayBlock() {
                    return true;
                }

                @Override
                public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                    return (value, topic, headers) -> new byte[0];
                }

                @Override
                public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                    return (data, topic, headers) -> null;
                }
            };
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(blockingJsonProvider));
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("blocking-json-false", new JsonObject().put("worker", false)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("blocking-json-false", kafkaConfig, PlainPayload.class, registry, violations);

            assertNull(entry, "worker=false with a mayBlock json provider must be rejected");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("worker=false")),
                    "Expected worker=false violation, got: " + violations);
        }
    }

    // ===========================================================================================
    // Model 2: custom deserializer
    // ===========================================================================================

    @Nested
    @DisplayName("Model 2: custom deserializer rules")
    class Model2CustomDeserializer {

        /** A minimal blocking custom deserializer. */
        static final KafkaDeserializer<PlainPayload> BLOCKING_CUSTOM_DESER = new KafkaDeserializer<>() {
            @Override
            public PlainPayload deserialize(byte[] data, String topic, Map<String, String> headers) {
                return new PlainPayload(new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public boolean mayBlock() {
                return true;
            }
        };

        /** A non-blocking custom deserializer (default mayBlock=false). */
        static final KafkaDeserializer<PlainPayload> NON_BLOCKING_CUSTOM_DESER =
                (data, topic, headers) -> new PlainPayload(new String(data, StandardCharsets.UTF_8));

        @Test
        @DisplayName("custom deserializer + endpoint-level format → violation, entry null")
        void customDeserWithEndpointFormatIsViolation() {
            // kafka.consumers.custom-ep-fmt.format=avro (endpoint-level)
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("custom-ep-fmt", new JsonObject().put("format", "avro")));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = buildBindingWithCustomDeser(
                    "custom-ep-fmt",
                    kafkaConfig,
                    PlainPayload.class,
                    NON_BLOCKING_CUSTOM_DESER,
                    avroRegistry(),
                    violations);

            assertNull(entry, "Entry must be null when custom deserializer is combined with endpoint format");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("custom deserializer") && v.contains("format")),
                    "Expected custom-deserializer+format violation, got: " + violations);
        }

        @Test
        @DisplayName("custom deserializer + global kafka.format only → entry built, custom deserializer used")
        void customDeserWithGlobalFormatOnly() {
            // kafka.format=avro (global-level only — NOT endpoint)
            JsonObject kafkaConfig = new JsonObject().put("format", "avro");
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = buildBindingWithCustomDeser(
                    "custom-global",
                    kafkaConfig,
                    PlainPayload.class,
                    NON_BLOCKING_CUSTOM_DESER,
                    avroRegistry(),
                    violations);

            assertTrue(
                    violations.isEmpty(),
                    "Global kafka.format must NOT conflict with custom deserializer, got: " + violations);
            assertNotNull(entry, "Entry must be built when only global format is set");
            assertSame(NON_BLOCKING_CUSTOM_DESER, entry.deserializer(), "Custom deserializer must be used");
        }

        @Test
        @DisplayName("blocking custom deserializer with absent worker config → forced to WORKER")
        void blockingCustomDeserForcesWorker() {
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = buildBindingWithCustomDeser(
                    "blocking-custom",
                    new JsonObject(),
                    PlainPayload.class,
                    BLOCKING_CUSTOM_DESER,
                    jsonRegistry(),
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertSame(BLOCKING_CUSTOM_DESER, entry.deserializer());
            assertEquals(
                    ThreadingModel.WORKER, entry.config().deploymentOptions().getThreadingModel());
        }
    }

    // ===========================================================================================
    // Explicit format with no provider
    // ===========================================================================================

    @Nested
    @DisplayName("Explicit format with no registered provider")
    class UnknownExplicitFormat {

        @Test
        @DisplayName("endpoint format=avro with no avro provider → violation naming the format, entry null")
        void endpointFormatWithNoProviderIsViolation() {
            // kafka.consumers.unknown-fmt.format=avro — but registry is JSON-only
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("unknown-fmt", new JsonObject().put("format", "avro")));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("unknown-fmt", kafkaConfig, PlainPayload.class, jsonRegistry(), violations);

            assertNull(entry, "Entry must be null when format has no provider");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("avro")),
                    "Expected violation naming the format 'avro', got: " + violations);
        }
    }

    // ===========================================================================================
    // ROUTER kind: provider required for property/payload routes
    // ===========================================================================================

    @Nested
    @DisplayName("ambiguous auto-detect is reported as a violation, not an uncaught throw")
    class AmbiguousAutoDetect {

        @Test
        @DisplayName("BINDING whose value type two providers auto-detect fails with a recorded violation")
        void bindingAmbiguousAutoDetect() {
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry =
                    buildBinding("amb-binding", new JsonObject(), FakeRecord.class, ambiguousRegistry(), violations);
            assertNull(entry, "Ambiguous auto-detect must reject the binding, not throw");
            assertTrue(
                    violations.stream()
                            .anyMatch(v -> v.contains("cannot resolve value format")
                                    && v.contains("avro")
                                    && v.contains("proto")),
                    "Violation must name the conflict: " + violations);
        }

        @Test
        @DisplayName("ROUTER whose route type two providers auto-detect fails with a recorded violation")
        void routerAmbiguousAutoDetect() {
            ResolvedKafkaConsumerConfig cfg = config("amb-router", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "amb-router",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(
                            new ConsumerEntry.RouteEntry(
                                    "", "type", "created", false, FakeRecord.class, "addr", null, false),
                            new ConsumerEntry.RouteEntry("", "", "", true, Void.class, "addr.default", null, false)),
                    null,
                    ambiguousRegistry(),
                    null,
                    null,
                    new HashSet<>(),
                    violations);
            assertNull(entry, "Ambiguous auto-detect must reject the router, not throw");
            assertTrue(
                    violations.stream()
                            .anyMatch(v -> v.contains("router cannot resolve value format")
                                    && v.contains("avro")
                                    && v.contains("proto")),
                    "Violation must name the conflict: " + violations);
        }

        @Test
        @DisplayName("a rejected (ambiguous) entry produces no spurious worker=false violation (no side effects)")
        void rejectedEntryHasNoMayBlockSideEffect() {
            // With a blocking 'json' provider, the resolve-failed entry's DEFAULT_FORMAT ('json') is
            // mayBlock=true; paired with worker=false the mayBlock block would add a spurious SECOND
            // violation onto the already-rejected entry if it ran. The if(valid) guard prevents that —
            // an invalid entry has no side effects.
            KafkaSerdeProvider blockingJson = new KafkaSerdeProvider() {
                @Override
                public String format() {
                    return "json";
                }

                @Override
                public boolean mayBlock() {
                    return true;
                }

                @Override
                public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                    return (value, topic, headers) -> new byte[0];
                }

                @Override
                public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                    return (data, topic, headers) -> null;
                }
            };
            KafkaSerdeRegistry registry =
                    new KafkaSerdeRegistry(Set.of(blockingJson, new FakeAvroProvider(), new SecondAvroLikeProvider()));
            JsonObject kafkaConfig = new JsonObject()
                    .put("consumers", new JsonObject().put("amb-reject", new JsonObject().put("worker", false)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = buildBinding("amb-reject", kafkaConfig, FakeRecord.class, registry, violations);

            assertNull(entry, "Ambiguous auto-detect must reject the binding");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("cannot resolve value format")),
                    "Expected the resolve-failure violation: " + violations);
            assertTrue(
                    violations.stream().noneMatch(v -> v.contains("worker=false")),
                    "Rejected entry must not also emit a spurious worker=false violation: " + violations);
        }
    }

    @Nested
    @DisplayName("ROUTER kind: json provider required for json-format property/payload routers")
    class RouterFormat {

        private static ConsumerEntry.RouteEntry propertyRoute(String matchValue, Class<?> valueType) {
            return new ConsumerEntry.RouteEntry("", "type", matchValue, false, valueType, "addr", null, false);
        }

        private static ConsumerEntry.RouteEntry headerRoute(String hdr, String matchValue, Class<?> valueType) {
            return new ConsumerEntry.RouteEntry(hdr, "", matchValue, false, valueType, "addr.hdr", null, false);
        }

        private static ConsumerEntry.RouteEntry defaultRoute(Class<?> valueType) {
            return new ConsumerEntry.RouteEntry("", "", "", true, valueType, "addr.default", null, false);
        }

        @Test
        @DisplayName("ROUTER with json provider resolves to 'json' and builds cleanly")
        void routerWithJsonProviderBuilds() {
            ResolvedKafkaConsumerConfig cfg = config("router-test", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "router-test",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", PlainPayload.class), defaultRoute(Void.class)),
                    null,
                    jsonRegistry(),
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat(), "Router must resolve to json when json provider is present");
        }

        @Test
        @DisplayName("ROUTER with a property route but no json provider fails with actionable violation")
        void routerWithPropertyRouteButNoJsonProviderFails() {
            ResolvedKafkaConsumerConfig cfg = config("router-nojson", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "router-nojson",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", PlainPayload.class), defaultRoute(Void.class)),
                    null,
                    emptyRegistry(),
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertNull(entry, "Router with property route and no provider must be rejected");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("json")),
                    "Violation must name the missing format: " + violations);
        }

        @Test
        @DisplayName("all-Void header/default-only router with no provider succeeds (nothing to deserialize)")
        void headerOnlyAllVoidRouterWithNoProviderSucceeds() {
            ResolvedKafkaConsumerConfig cfg = config("hdr-void-router", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "hdr-void-router",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(headerRoute("event-type", "created", Void.class), defaultRoute(Void.class)),
                    null,
                    emptyRegistry(),
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertTrue(violations.isEmpty(), "Header/default-only all-Void router needs no provider: " + violations);
            assertNotNull(entry, "Entry must be built for a header/default-only all-Void router");
        }

        @Test
        @DisplayName("all-Void property router requires a provider (property route still needs routing SPI)")
        void allVoidPropertyRouterRequiresProvider() {
            // A property route needs the routing SPI even when valueType is Void
            ResolvedKafkaConsumerConfig cfg = config("prop-void-router", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "prop-void-router",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", Void.class), defaultRoute(Void.class)),
                    null,
                    emptyRegistry(),
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertNull(entry, "All-Void property router with no provider must be rejected");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("json")),
                    "Violation must name the missing format: " + violations);
        }

        @Test
        @DisplayName("a mayBlock json provider on a router forces WORKER threading")
        void mayBlockJsonProviderOnRouterForcesWorker() {
            KafkaSerdeProvider blockingJsonProvider = new KafkaSerdeProvider() {
                @Override
                public String format() {
                    return "json";
                }

                @Override
                public boolean mayBlock() {
                    return true;
                }

                @Override
                public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                    return (value, topic, headers) -> new byte[0];
                }

                @Override
                public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                    return (data, topic, headers) -> null;
                }
            };
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(blockingJsonProvider));
            ResolvedKafkaConsumerConfig cfg = config("blocking-json-router", new JsonObject());
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "blocking-json-router",
                    cfg,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", PlainPayload.class), defaultRoute(Void.class)),
                    null,
                    registry,
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals("json", entry.valueFormat());
            assertEquals(
                    ThreadingModel.WORKER,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "A mayBlock json router must force WORKER threading");
        }

        @Test
        @DisplayName("a mayBlock json provider on a router with worker=false is rejected")
        void mayBlockJsonProviderOnRouterWithWorkerFalseIsViolation() {
            KafkaSerdeProvider blockingJsonProvider = new KafkaSerdeProvider() {
                @Override
                public String format() {
                    return "json";
                }

                @Override
                public boolean mayBlock() {
                    return true;
                }

                @Override
                public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                    return (value, topic, headers) -> new byte[0];
                }

                @Override
                public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                    return (data, topic, headers) -> null;
                }
            };
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(blockingJsonProvider));
            JsonObject kafkaConfig = new JsonObject()
                    .put(
                            "consumers",
                            new JsonObject().put("blocking-json-router-false", new JsonObject().put("worker", false)));
            List<String> violations = new ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "blocking-json-router-false",
                    config("blocking-json-router-false", kafkaConfig),
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", PlainPayload.class), defaultRoute(Void.class)),
                    null,
                    registry,
                    null,
                    null,
                    new HashSet<>(),
                    violations);

            assertNull(entry, "worker=false with a mayBlock json router must be rejected");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("worker=false")),
                    "Expected worker=false violation, got: " + violations);
        }
    }
}
