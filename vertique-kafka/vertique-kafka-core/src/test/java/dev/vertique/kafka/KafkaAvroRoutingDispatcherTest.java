// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.KafkaRecordDispatcher.RouteResult;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the schema-driven (Avro-style) router path in {@link KafkaRecordDispatcher}:
 * type-agnostic routing deserializer + {@code matchValue} routing via the SPI, header/default
 * selection without deserialization, payload reuse via {@code convertRouted}, the
 * type-assignability guard, and {@code Void} routes.
 *
 * <p>A {@link FakeAvroProvider} stands in for a real Avro provider — the dispatcher never
 * references {@code org.apache.avro}; all format-specific access goes through
 * {@link KafkaSerdeRegistry}.
 */
class KafkaAvroRoutingDispatcherTest {

    // --- Fake records & provider ---

    /** A discriminator-bearing fake "Avro" record (the wire schema-id resolves the concrete type). */
    record OrderCreated(String type, String id) {}

    record OrderShipped(String type, String id) {}

    /**
     * Fake provider whose {@code routingDeserializer} parses {@code "type:id"} bytes into the
     * concrete record named by {@code type}, and whose {@code matchValue} reads the {@code "type"}
     * discriminator. {@code mayBlock=true} like a real registry serde.
     */
    static final class FakeAvroProvider implements KafkaSerdeProvider {
        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return type == OrderCreated.class || type == OrderShipped.class;
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == OrderCreated.class || type == OrderShipped.class;
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            return (data, topic, headers) -> (V) toRecord(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) {
            return (data, topic, headers) -> toRecord(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public String matchValue(Object deserializedValue, String property) {
            if (!"type".equals(property)) {
                return null;
            }
            if (deserializedValue instanceof OrderCreated o) {
                return o.type();
            }
            if (deserializedValue instanceof OrderShipped o) {
                return o.type();
            }
            return null;
        }

        private static Object toRecord(String payload) {
            String[] parts = payload.split(":", 2);
            String type = parts[0];
            String id = parts.length > 1 ? parts[1] : "";
            return switch (type) {
                case "created" -> new OrderCreated(type, id);
                case "shipped" -> new OrderShipped(type, id);
                default -> new OrderCreated("unknown", id);
            };
        }
    }

    private static final KafkaSerdeRegistry AVRO = new KafkaSerdeRegistry(Set.of(new FakeAvroProvider()));

    // --- Entry / dispatcher builders ---

    private static ConsumerEntry.RouteEntry propertyRoute(String matchValue, Class<?> valueType) {
        return new ConsumerEntry.RouteEntry(
                "", "type", matchValue, false, valueType, "addr." + matchValue, null, false);
    }

    private static ConsumerEntry.RouteEntry headerRoute(String header, String matchValue, Class<?> valueType) {
        return new ConsumerEntry.RouteEntry(header, "", matchValue, false, valueType, "addr.hdr", null, false);
    }

    private static ConsumerEntry.RouteEntry defaultRoute(Class<?> valueType) {
        return new ConsumerEntry.RouteEntry("", "", "", true, valueType, "addr.default", null, false);
    }

    /**
     * Resolves a consumer config from the raw {@code kafka} section JSON, parsing it into the typed
     * {@link KafkaConfig} at the boundary exactly as the runtime does.
     */
    private static ResolvedKafkaConsumerConfig resolveConsumer(String name, JsonObject kafkaConfigJson) {
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafkaConfigJson), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                "orders",
                "grp",
                true,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                30_000L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    private static ConsumerEntry routerEntry(List<ConsumerEntry.RouteEntry> routes) {
        ResolvedKafkaConsumerConfig config = resolveConsumer("avro-router", new JsonObject());
        // Build the entry through the shared validation funnel so valueFormat resolves to "avro".
        return KafkaConsumerValidation.validateAndBuild(
                "avro-router",
                config,
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                routes,
                null,
                AVRO,
                null,
                null,
                new java.util.HashSet<>(),
                new java.util.ArrayList<>());
    }

    private static KafkaRecordDispatcher dispatcher(ConsumerEntry entry) {
        Map<Class<?>, KafkaDeserializer<?>> routeDeserializers = new java.util.HashMap<>();
        for (ConsumerEntry.RouteEntry route : entry.routes()) {
            if (route.valueType() != null && route.valueType() != Void.class) {
                routeDeserializers.computeIfAbsent(
                        route.valueType(),
                        vt -> AVRO.deserializer(
                                entry.valueFormat(), vt, entry.config().serdeConfig()));
            }
        }
        return new KafkaRecordDispatcher(entry, routeDeserializers, AVRO, null, null, null, null, null);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // --- Tests ---

    @Nested
    @DisplayName("router resolves to avro format and forces worker threading")
    class FormatResolution {

        @Test
        @DisplayName("a router over Avro route types resolves valueFormat=avro and WORKER threading")
        void routerFormatAndThreading() {
            ConsumerEntry entry =
                    routerEntry(List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            assertEquals("avro", entry.valueFormat());
            assertEquals(
                    io.vertx.core.ThreadingModel.WORKER,
                    entry.config().deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("a Void-only property router honors an explicit avro format (does not fall back to json)")
        void voidOnlyRouterHonorsExplicitFormat() {
            // Every route is a no-arg property handler (valueType Void) — there is no payload type to
            // auto-detect from, but a global/endpoint format=avro must still apply (Codex finding).
            ResolvedKafkaConsumerConfig config = resolveConsumer("void-router", new JsonObject().put("format", "avro"));
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "void-router",
                    config,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("created", Void.class), defaultRoute(Void.class)),
                    null,
                    AVRO,
                    null,
                    null,
                    new java.util.HashSet<>(),
                    new java.util.ArrayList<>());
            assertEquals("avro", entry.valueFormat(), "explicit avro must win even with only Void routes");
            assertEquals(
                    io.vertx.core.ThreadingModel.WORKER,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "avro router must force worker threading even when Void-only");
        }
    }

    @Nested
    @DisplayName("header/default-only non-JSON router needs no routing support")
    class HeaderOnlyRouter {

        /** An avro provider that supports normal deserialize but NOT property routing (default throws). */
        static final class RoutingUnsupportedProvider implements KafkaSerdeProvider {
            @Override
            public String format() {
                return "avro";
            }

            @Override
            public boolean autoDetects(Class<?> type) {
                return type == OrderCreated.class || type == OrderShipped.class;
            }

            @Override
            public boolean mayBlock() {
                return true;
            }

            @Override
            public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
                return (value, topic, headers) -> value.toString().getBytes(StandardCharsets.UTF_8);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
                return (data, topic, headers) ->
                        (V) new OrderCreated("created", new String(data, StandardCharsets.UTF_8));
            }
            // routingDeserializer intentionally NOT overridden — default throws UnsupportedOperationException.
        }

        @Test
        @DisplayName("a header-only avro router builds + dispatches without requiring a routing deserializer")
        void headerOnlyRouterBuilds() throws Exception {
            KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(new RoutingUnsupportedProvider()));
            ResolvedKafkaConsumerConfig config = resolveConsumer("hdr-router", new JsonObject());
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "hdr-router",
                    config,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(headerRoute("event-type", "created", OrderCreated.class), defaultRoute(Void.class)),
                    null,
                    registry,
                    null,
                    null,
                    new java.util.HashSet<>(),
                    new java.util.ArrayList<>());
            assertEquals("avro", entry.valueFormat());

            Map<Class<?>, KafkaDeserializer<?>> routeDeserializers = new java.util.HashMap<>();
            routeDeserializers.put(
                    OrderCreated.class,
                    registry.deserializer(
                            "avro", OrderCreated.class, entry.config().serdeConfig()));
            // Must not throw despite the provider not supporting routingDeserializer.
            KafkaRecordDispatcher d =
                    new KafkaRecordDispatcher(entry, routeDeserializers, registry, null, null, null, null, null);
            RouteResult result = d.resolveRoute(Map.of("event-type", "created"), bytes("1"), "orders");
            assertEquals("created", result.route().matchValue());
        }
    }

    @Nested
    @DisplayName("property-based routing via the SPI")
    class PropertyRouting {

        @Test
        @DisplayName("matches the property route and reuses the deserialized record as payload")
        void propertyMatchReusesRecord() throws Exception {
            ConsumerEntry entry = routerEntry(List.of(
                    propertyRoute("created", OrderCreated.class),
                    propertyRoute("shipped", OrderShipped.class),
                    defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of(), bytes("shipped:42"), "orders");
            assertEquals("shipped", result.route().matchValue());
            // routingValue carries the decoded record (no parsedTree in new shape)
            assertSame(
                    result.routingValue(),
                    d.deserializeRecord(record("orders"), bytes("shipped:42"), Map.of(), result),
                    "matched payload must reuse the routingValue (no second deserialize)");
            assertEquals(new OrderShipped("shipped", "42"), result.routingValue());
        }

        @Test
        @DisplayName("falls through to the default route when no property matches (routingValue discarded)")
        void noPropertyMatchFallsToDefault() {
            ConsumerEntry entry =
                    routerEntry(List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of(), bytes("shipped:7"), "orders");
            assertTrue(result.route().defaultHandler(), "Unmatched property should hit the default route");
            assertNull(result.routingValue());
        }
    }

    @Nested
    @DisplayName("header / default selection without deserialization")
    class HeaderSelection {

        @Test
        @DisplayName("header match selects without deserializing; payload uses the route deserializer")
        void headerMatchNoPreDeserialize() throws Exception {
            ConsumerEntry entry = routerEntry(
                    List.of(headerRoute("event-type", "created", OrderCreated.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of("event-type", "created"), bytes("created:1"), "orders");
            assertEquals("created", result.route().matchValue());
            assertNull(result.routingValue(), "Header match must not pre-deserialize");

            Object payload = d.deserializeRecord(record("orders"), bytes("created:1"), Map.of(), result);
            assertEquals(new OrderCreated("created", "1"), payload);
        }
    }

    @Nested
    @DisplayName("type-assignability guard via convertRouted")
    class TypeGuard {

        @Test
        @DisplayName("a record not assignable to the matched route's value type fails with DeserializationException")
        void wrongTypeFailsFast() {
            // Route declares OrderShipped for discriminator "created", but the record is an OrderCreated.
            ConsumerEntry entry =
                    routerEntry(List.of(propertyRoute("created", OrderShipped.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of(), bytes("created:9"), "orders");
            assertEquals("created", result.route().matchValue());
            // The SPI default convertRouted throws DeserializationException when routingValue is not
            // assignable to the route type (FakeAvroProvider inherits the default).
            assertThrows(
                    DeserializationException.class,
                    () -> d.deserializeRecord(record("orders"), bytes("created:9"), Map.of(), result));
        }
    }

    @Nested
    @DisplayName("Void routes skip payload deserialization")
    class VoidRoutes {

        @Test
        @DisplayName("a matched Void route yields a null payload")
        void voidRouteNullPayload() throws Exception {
            ConsumerEntry entry =
                    routerEntry(List.of(headerRoute("event-type", "ping", Void.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of("event-type", "ping"), bytes("ping:0"), "orders");
            assertNull(d.deserializeRecord(record("orders"), bytes("ping:0"), Map.of(), result));
        }
    }

    @Nested
    @DisplayName("mixed-format router is rejected at build time")
    class MixedFormat {

        record PlainEvent(String name) {}

        @Test
        @DisplayName("a router mixing avro and json route types fails fast (returns null entry)")
        void mixedFormatRejected() {
            // PlainEvent is not auto-detected by FakeAvroProvider, so it resolves to json;
            // OrderCreated is auto-detected as avro — mixed formats → rejection.
            KafkaSerdeRegistry avroAndJson = new KafkaSerdeRegistry(
                    Set.of(new FakeAvroProvider(), new dev.vertique.kafka.serialization.TestJsonSerdeProvider()));
            ResolvedKafkaConsumerConfig config = resolveConsumer("mixed-router", new JsonObject());
            java.util.List<String> violations = new java.util.ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "mixed-router",
                    config,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(
                            propertyRoute("created", OrderCreated.class),
                            propertyRoute("plain", PlainEvent.class),
                            defaultRoute(Void.class)),
                    null,
                    avroAndJson,
                    null,
                    null,
                    new java.util.HashSet<>(),
                    violations);
            assertNull(entry, "Mixed-format router must fail validation");
        }

        @Test
        @DisplayName("an explicit-avro router with a route type the provider does not support fails at registration")
        void unsupportedRouteTypeRejected() {
            // Global format=avro makes every route resolve to avro (explicit wins), so PlainEvent does
            // not trip the mixed-format check — it must instead be rejected as an unsupported type.
            ResolvedKafkaConsumerConfig config = resolveConsumer("bad-router", new JsonObject().put("format", "avro"));
            java.util.List<String> violations = new java.util.ArrayList<>();
            ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                    "bad-router",
                    config,
                    ConsumerEntry.Kind.ROUTER,
                    null,
                    null,
                    null,
                    false,
                    List.of(propertyRoute("plain", PlainEvent.class), defaultRoute(Void.class)),
                    null,
                    AVRO,
                    null,
                    null,
                    new java.util.HashSet<>(),
                    violations);
            assertNull(entry, "Unsupported route type must fail registration");
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("not valid for format 'avro'")),
                    "Expected an unsupported-route-type violation, got: " + violations);
        }
    }

    // --- Helpers ---

    private static io.vertx.kafka.client.consumer.KafkaConsumerRecord<String, byte[]> record(String topic) {
        @SuppressWarnings("unchecked")
        io.vertx.kafka.client.consumer.KafkaConsumerRecord<String, byte[]> rec =
                org.mockito.Mockito.mock(io.vertx.kafka.client.consumer.KafkaConsumerRecord.class);
        org.mockito.Mockito.when(rec.topic()).thenReturn(topic);
        return rec;
    }
}
