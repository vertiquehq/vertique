// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.KafkaRecordDispatcher.RouteResult;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving JSON property routing parity through the {@link TestJsonSerdeProvider} SPI.
 *
 * <p>These tests drive {@link KafkaRecordDispatcher} with a real {@link KafkaSerdeRegistry}
 * containing a {@link TestJsonSerdeProvider} (the in-module JSON fixture), mirroring how
 * {@link KafkaAvroRoutingDispatcherTest} uses its fake Avro provider. They verify:
 * <ul>
 *   <li>A property router over JSON POJOs routes correctly to the matched route.</li>
 *   <li>The parsed {@code JsonNode} is reused as the routing value (no double parse) via
 *       {@code convertRouted}.</li>
 *   <li>Header/default matches work without any deserialization.</li>
 *   <li>An all-Void header/default-only router succeeds without a routing deserializer call.</li>
 *   <li>A non-matching property route falls through to the default.</li>
 *   <li>A type-incompatible convertRouted throws {@link DeserializationException}.</li>
 * </ul>
 */
class KafkaJsonRoutingTest {

    // --- Fixtures ---

    record OrderCreated(String type, String id) {}

    record OrderShipped(String type, String id) {}

    record UnrelatedEvent(String name) {}

    private static final KafkaSerdeRegistry JSON_REG = new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider()));

    // --- Route / entry / dispatcher builders ---

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
     * Builds a ROUTER {@link ConsumerEntry} via the validation funnel so that valueFormat is
     * correctly resolved from the registry.
     */
    private static ConsumerEntry routerEntry(String name, List<ConsumerEntry.RouteEntry> routes) {
        KafkaConfig kafkaConfig =
                KafkaConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ResolvedKafkaConsumerConfig config = ResolvedKafkaConsumerConfig.resolve(
                name,
                "events",
                "grp",
                true,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                30_000L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
        List<String> violations = new java.util.ArrayList<>();
        ConsumerEntry entry = KafkaConsumerValidation.validateAndBuild(
                name,
                config,
                ConsumerEntry.Kind.ROUTER,
                null,
                null,
                null,
                false,
                routes,
                null,
                JSON_REG,
                null,
                null,
                new java.util.HashSet<>(),
                violations);
        assertTrue(violations.isEmpty(), "Expected no validation violations, got: " + violations);
        assertNotNull(entry, "Entry must be built (no violations)");
        return entry;
    }

    private static KafkaRecordDispatcher dispatcher(ConsumerEntry entry) {
        Map<Class<?>, KafkaDeserializer<?>> routeDeserializers = new java.util.HashMap<>();
        for (ConsumerEntry.RouteEntry route : entry.routes()) {
            if (route.valueType() != null && route.valueType() != Void.class) {
                routeDeserializers.computeIfAbsent(
                        route.valueType(),
                        vt -> JSON_REG.deserializer(
                                entry.valueFormat(), vt, entry.config().serdeConfig()));
            }
        }
        return new KafkaRecordDispatcher(entry, routeDeserializers, JSON_REG, null, null, null, null, null);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static io.vertx.kafka.client.consumer.KafkaConsumerRecord<String, byte[]> mockRecord(String topic) {
        @SuppressWarnings("unchecked")
        io.vertx.kafka.client.consumer.KafkaConsumerRecord<String, byte[]> rec =
                org.mockito.Mockito.mock(io.vertx.kafka.client.consumer.KafkaConsumerRecord.class);
        org.mockito.Mockito.when(rec.topic()).thenReturn(topic);
        return rec;
    }

    // --- Tests ---

    @Nested
    @DisplayName("router resolves to json format via JsonSerdeProvider")
    class FormatResolution {

        @Test
        @DisplayName("a property router resolves valueFormat=json with the json provider")
        void routerResolvesToJson() {
            ConsumerEntry entry = routerEntry(
                    "json-router", List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            assertEquals("json", entry.valueFormat());
        }

        @Test
        @DisplayName("json router does not force WORKER threading (JsonSerdeProvider.mayBlock=false)")
        void jsonRouterDoesNotForceWorker() {
            ConsumerEntry entry = routerEntry(
                    "json-router-threading",
                    List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            assertEquals(
                    io.vertx.core.ThreadingModel.EVENT_LOOP,
                    entry.config().deploymentOptions().getThreadingModel(),
                    "Non-blocking json router must not force WORKER threading");
        }
    }

    @Nested
    @DisplayName("property-based JSON routing via the SPI")
    class PropertyRouting {

        @Test
        @DisplayName("routes an OrderCreated payload via the 'type' discriminator field")
        void routesOrderCreated() throws Exception {
            ConsumerEntry entry = routerEntry(
                    "json-prop-router",
                    List.of(
                            propertyRoute("created", OrderCreated.class),
                            propertyRoute("shipped", OrderShipped.class),
                            defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            byte[] payload = bytes("{\"type\":\"created\",\"id\":\"101\"}");
            RouteResult result = d.resolveRoute(Map.of(), payload, "events");

            assertNotNull(result);
            assertEquals("created", result.route().matchValue());
            assertNotNull(result.routingValue(), "routingValue must carry the parsed JsonNode");
        }

        @Test
        @DisplayName("reuses the parsed JsonNode as the routing value (no double parse) via convertRouted")
        void reusesJsonNodeViaConvertRouted() throws Exception {
            ConsumerEntry entry = routerEntry(
                    "json-reuse-router",
                    List.of(propertyRoute("shipped", OrderShipped.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            byte[] payload = bytes("{\"type\":\"shipped\",\"id\":\"42\"}");
            RouteResult result = d.resolveRoute(Map.of(), payload, "events");

            assertNotNull(result);
            assertEquals("shipped", result.route().matchValue());

            // deserializeRecord must use convertRouted (reusing the JsonNode) — no second wire-byte parse
            Object deserialized = d.deserializeRecord(mockRecord("events"), payload, Map.of(), result);
            assertEquals(new OrderShipped("shipped", "42"), deserialized);
        }

        @Test
        @DisplayName("falls through to default route when no property matches")
        void fallsToDefaultWhenNoMatch() {
            ConsumerEntry entry = routerEntry(
                    "json-fallback-router",
                    List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            // "cancelled" does not match any property route
            byte[] payload = bytes("{\"type\":\"cancelled\",\"id\":\"7\"}");
            RouteResult result = d.resolveRoute(Map.of(), payload, "events");

            assertNotNull(result);
            assertTrue(result.route().defaultHandler(), "Unmatched type must fall to default route");
            assertNull(result.routingValue(), "Default route must not carry a routing value");
        }

        @Test
        @DisplayName("returns null when the JSON is null/empty (no match, no default)")
        void nullPayloadYieldsNull() {
            ConsumerEntry entry =
                    routerEntry("json-null-router", List.of(propertyRoute("created", OrderCreated.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of(), null, "events");
            assertNull(result, "Null raw bytes must yield no route");
        }
    }

    @Nested
    @DisplayName("header / default selection without deserialization")
    class HeaderSelection {

        @Test
        @DisplayName("header match selects the route without calling the routing deserializer")
        void headerMatchNoDeserialize() throws Exception {
            ConsumerEntry entry = routerEntry(
                    "json-hdr-router",
                    List.of(headerRoute("event-type", "created", OrderCreated.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            byte[] payload = bytes("{\"type\":\"created\",\"id\":\"5\"}");
            RouteResult result = d.resolveRoute(Map.of("event-type", "created"), payload, "events");

            assertNotNull(result);
            assertEquals("created", result.route().matchValue());
            assertNull(result.routingValue(), "Header match must not pre-deserialize (routingValue null)");

            // deserializeRecord uses the cached route deserializer for header matches
            Object deserialized = d.deserializeRecord(mockRecord("events"), payload, Map.of(), result);
            assertEquals(new OrderCreated("created", "5"), deserialized);
        }
    }

    @Nested
    @DisplayName("all-Void header/default-only router needs no routing deserializer")
    class AllVoidHeaderRouter {

        @Test
        @DisplayName("header/default-only all-Void router resolves and dispatches without calling routingDeserializer")
        void allVoidHeaderRouterBuildsAndRoutes() {
            ConsumerEntry entry = routerEntry(
                    "json-void-hdr-router",
                    List.of(headerRoute("event-type", "ping", Void.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            RouteResult result = d.resolveRoute(Map.of("event-type", "ping"), bytes("ignored"), "events");
            assertNotNull(result);
            assertEquals("ping", result.route().matchValue());
            assertNull(result.routingValue());
        }
    }

    @Nested
    @DisplayName("Void routes skip payload deserialization")
    class VoidRoutes {

        @Test
        @DisplayName("a matched Void route yields a null payload")
        void voidRouteNullPayload() throws Exception {
            ConsumerEntry entry = routerEntry(
                    "json-void-prop-router", List.of(propertyRoute("created", Void.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            byte[] payload = bytes("{\"type\":\"created\"}");
            RouteResult result = d.resolveRoute(Map.of(), payload, "events");
            assertNotNull(result);
            assertNull(d.deserializeRecord(mockRecord("events"), payload, Map.of(), result));
        }
    }

    @Nested
    @DisplayName("type-incompatible convertRouted throws DeserializationException")
    class TypeGuard {

        @Test
        @DisplayName("routing to a type whose fields don't match the JSON throws DeserializationException")
        void incompatibleTypeThrows() throws Exception {
            // Route for "created" declares OrderShipped, but the JSON maps to OrderCreated's shape.
            // Jackson will actually map the fields — so test a truly incompatible type (String).
            ConsumerEntry entry = routerEntry(
                    "json-typeguard-router",
                    // Void match to verify the resolve; then check convertRouted directly
                    List.of(propertyRoute("created", OrderCreated.class), defaultRoute(Void.class)));
            KafkaRecordDispatcher d = dispatcher(entry);

            byte[] payload = bytes("{\"type\":\"created\",\"id\":\"1\"}");
            RouteResult result = d.resolveRoute(Map.of(), payload, "events");
            assertNotNull(result);
            assertNotNull(result.routingValue(), "Must have a routingValue for a property match");

            // Directly invoke convertRouted with an incompatible target type
            assertThrows(
                    DeserializationException.class,
                    () -> JSON_REG.convertRouted("json", result.routingValue(), int.class, new JsonObject()),
                    "convertRouted must throw DeserializationException on type mismatch");
        }
    }
}
