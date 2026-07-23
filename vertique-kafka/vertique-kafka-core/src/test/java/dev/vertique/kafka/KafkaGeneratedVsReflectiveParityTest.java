// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Parity test verifying that the generated path ({@link GeneratedBindingMetaLoader} converters)
 * produces {@link ConsumerEntry} instances whose observable fields match those produced by the
 * reflective path ({@link KafkaConsumerScanner} driven through {@link KafkaConsumerRegistrar#scan})
 * for the same logical consumer configuration.
 *
 * <p>Strategy: for each tested model the reflective path is driven by calling
 * {@link KafkaConsumerRegistrar#scan} with fixture classes that have <em>no</em> generated
 * companion (so the scanner falls back to reflection), producing a reference {@link ConsumerEntry}.
 * The generated path is exercised by calling the corresponding
 * {@link GeneratedBindingMetaLoader#toRouterEntry}/{@link GeneratedBindingMetaLoader#toHandlerEntry}/
 * {@link GeneratedBindingMetaLoader#toSourceEntry} converter with a hand-built {@link KafkaBindingMeta}
 * that carries the same data the processor would have emitted. Equality of all observable
 * {@link ConsumerEntry} fields then proves the two paths converge.
 *
 * <p>Covered models:
 * <ul>
 *   <li>Model 3 — {@link KafkaListener} routing interface with a {@link DispatchTo} route and a
 *       default route.</li>
 *   <li>Model 4 — {@link KafkaRecordHandler} instance.</li>
 *   <li>Model 1 — {@code @KafkaSource} on a service implementation method.</li>
 * </ul>
 */
class KafkaGeneratedVsReflectiveParityTest {

    // --- Shared event payload ---

    /** Simple event payload used across all parity fixtures. */
    record ParityEvent(String id) {}

    // --- Model 1 fixtures (no companion on classpath → reflective fallback) ---

    /** Contract for the Model-1 parity fixture. */
    @ServiceContract(value = "parity-source-service")
    interface ParitySourceService {

        /** Operation annotated with {@link ServiceOperation} so stable-target-id is resolvable. */
        @ServiceOperation("consume-event")
        @OneWay
        Future<Void> consumeEvent(ParityEvent event);
    }

    /** Implementation that carries the {@link KafkaSource} annotation (Model 1). */
    static class ParitySourceServiceImpl implements ParitySourceService {

        @KafkaSource(topic = "parity.source.events", groupId = "parity-source-grp")
        @Override
        public Future<Void> consumeEvent(ParityEvent event) {
            return Future.succeededFuture();
        }
    }

    // --- Model 3 fixtures ---

    /** Target service for the router's dispatch route. */
    @ServiceContract(value = "parity-router-target")
    interface ParityRouterTarget {

        /** Operation the router dispatches created-events to. */
        @ServiceOperation("handle-created")
        @OneWay
        Future<Void> handleCreated(ParityEvent event);
    }

    /** Implementation of the router target service. */
    static class ParityRouterTargetImpl implements ParityRouterTarget {

        @Override
        public Future<Void> handleCreated(ParityEvent event) {
            return Future.succeededFuture();
        }
    }

    /**
     * Model-3 routing interface. Has no generated companion so the reflective path
     * is exercised when passed to {@link KafkaConsumerRegistrar#scan}.
     */
    @KafkaListener(name = "parity-router", topic = "parity.router.events", groupId = "parity-router-grp")
    interface ParityRouter {

        /** Dispatches records with header {@code event-type=created} to {@link ParityRouterTarget}. */
        @KafkaHandler(matchHeader = "event-type", matchValue = "created")
        @DispatchTo(service = ParityRouterTarget.class, operation = "handle-created")
        void onCreated(ParityEvent event);

        /** Default catch-all route with no dispatch. */
        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    // --- Model 4 fixtures ---

    /**
     * Model-4 handler class. Has no generated companion so the reflective path
     * is exercised when its instance is passed to {@link KafkaConsumerRegistrar#scan}.
     */
    @KafkaListener(name = "parity-handler", topic = "parity.handler.events", groupId = "parity-handler-grp")
    static class ParityHandler implements KafkaRecordHandler<ParityEvent> {

        @Override
        public Future<Void> handle(KafkaMessage<ParityEvent> message) {
            return Future.succeededFuture();
        }
    }

    // --- Shared helpers ---

    /** JSON-capable serde registry shared by both paths so format/threading resolution is identical. */
    private static final dev.vertique.kafka.serialization.KafkaSerdeRegistry PARITY_SERDE =
            new dev.vertique.kafka.serialization.KafkaSerdeRegistry(
                    Set.of(new dev.vertique.kafka.serialization.TestJsonSerdeProvider()));

    /**
     * Returns a lenient {@link ConfigParser} for test use, matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /** Empty typed Kafka config used by the parity calls (no per-endpoint config configured). */
    private static final dev.vertique.kafka.config.KafkaConfig EMPTY_KAFKA_CONFIG =
            dev.vertique.kafka.config.KafkaConfig.fromConfig(
                    new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient()));

    /** Empty per-consumer config index for the GBML conversion parity calls. */
    private static final java.util.Map<String, dev.vertique.kafka.config.KafkaConsumerConfig> EMPTY_CONSUMER_INDEX =
            java.util.Map.of();

    /**
     * Fake {@code avro} provider that auto-detects {@link ParityEvent} and reports {@code mayBlock},
     * used to prove the format-bearing parity gate: both paths must resolve the same effective format
     * and threading model.
     */
    static final class FakeAvroProvider implements dev.vertique.kafka.serialization.KafkaSerdeProvider {
        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            return ParityEvent.class.isAssignableFrom(type);
        }

        @Override
        public boolean mayBlock() {
            return true;
        }

        @Override
        public <V> dev.vertique.kafka.serialization.KafkaSerializer<V> serializer(
                Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        public <V> dev.vertique.kafka.serialization.KafkaDeserializer<V> deserializer(
                Class<V> type, JsonObject endpointConfig) {
            return (data, topic, headers) -> null;
        }
    }

    /** Serde registry with a fake avro provider; both parity paths use it for the format-bearing case. */
    private static final dev.vertique.kafka.serialization.KafkaSerdeRegistry AVRO_PARITY =
            new dev.vertique.kafka.serialization.KafkaSerdeRegistry(Set.of(new FakeAvroProvider()));

    /** Builds a {@link KafkaConsumerRegistrar} backed by a fresh instance. */
    private static KafkaConsumerRegistrar registrar() {
        return new KafkaConsumerRegistrar();
    }

    /**
     * Drives {@link KafkaConsumerRegistrar#scan} with the given parameters and returns the
     * resulting entries.
     *
     * @param bindings  Model-2 bindings (usually empty for these tests)
     * @param handlers  Model-3/4 handler contributions
     * @param registry  service contract registry
     * @return entries produced by the reflective path
     */
    private static List<ConsumerEntry> scanReflective(
            Set<KafkaConsumerBinding<?>> bindings, Set<Object> handlers, ServiceContractRegistry registry) {
        ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
        return registrar().scan(bindings, handlers, registry, resolver, PARITY_SERDE, EMPTY_KAFKA_CONFIG);
    }

    /** Creates a fresh, empty set of used names. */
    private static Set<String> usedNames() {
        return new HashSet<>();
    }

    /** Creates a fresh, empty violations list. */
    private static List<String> violations() {
        return new ArrayList<>();
    }

    // --- Assertion helpers ---

    /**
     * Asserts that the observable fields of two {@link ConsumerEntry} instances are identical,
     * excluding the {@code deserializer} field (which is a Jackson wrapper whose identity is
     * not structurally comparable — see divergence note in class-level javadoc).
     *
     * @param reflective the entry produced by the reflective scanner
     * @param generated  the entry produced by the {@link GeneratedBindingMetaLoader} converter
     */
    private static void assertEntriesEqual(ConsumerEntry reflective, ConsumerEntry generated) {
        assertEquals(reflective.name(), generated.name(), "name");
        assertEquals(reflective.kind(), generated.kind(), "kind");
        assertEquals(reflective.valueType(), generated.valueType(), "valueType");
        assertEquals(reflective.targetAddress(), generated.targetAddress(), "targetAddress");
        assertEquals(reflective.stableTargetId(), generated.stableTargetId(), "stableTargetId");
        assertEquals(reflective.targetOneWay(), generated.targetOneWay(), "targetOneWay");
        assertEquals(reflective.config().topic(), generated.config().topic(), "config.topic");
        assertEquals(reflective.config().groupId(), generated.config().groupId(), "config.groupId");
        assertEquals(reflective.config().errorStrategy(), generated.config().errorStrategy(), "config.errorStrategy");
        assertEquals(
                reflective.config().commitStrategy(), generated.config().commitStrategy(), "config.commitStrategy");
        assertEquals(reflective.valueFormat(), generated.valueFormat(), "valueFormat");
        assertEquals(
                reflective.config().deploymentOptions().getThreadingModel(),
                generated.config().deploymentOptions().getThreadingModel(),
                "config.deploymentOptions.threadingModel");
        assertRoutesEqual(reflective.routes(), generated.routes());
    }

    /**
     * Asserts that two {@link ConsumerEntry.RouteEntry} lists contain the same routes on all
     * observable fields, regardless of list order.
     *
     * <p>Route ordering is not part of the parity contract: the reflective path uses
     * {@link Class#getMethods()}, which returns methods in an unspecified order, while the
     * generated path preserves the order in the {@link KafkaBindingMeta} routes list. Both sets of
     * routes are canonically sorted (non-default routes by {@code matchHeader+matchProperty+matchValue},
     * the default route last) before element-wise comparison.
     *
     * @param reflective route list produced by the reflective scanner
     * @param generated  route list produced by the {@link GeneratedBindingMetaLoader} converter
     */
    private static void assertRoutesEqual(
            List<ConsumerEntry.RouteEntry> reflective, List<ConsumerEntry.RouteEntry> generated) {
        assertEquals(reflective.size(), generated.size(), "routes.size");
        List<ConsumerEntry.RouteEntry> sortedR = sortRoutes(reflective);
        List<ConsumerEntry.RouteEntry> sortedG = sortRoutes(generated);
        for (int i = 0; i < sortedR.size(); i++) {
            ConsumerEntry.RouteEntry r = sortedR.get(i);
            ConsumerEntry.RouteEntry g = sortedG.get(i);
            assertEquals(r.matchHeader(), g.matchHeader(), "routes[" + i + "].matchHeader");
            assertEquals(r.matchProperty(), g.matchProperty(), "routes[" + i + "].matchProperty");
            assertEquals(r.matchValue(), g.matchValue(), "routes[" + i + "].matchValue");
            assertEquals(r.defaultHandler(), g.defaultHandler(), "routes[" + i + "].defaultHandler");
            assertEquals(r.valueType(), g.valueType(), "routes[" + i + "].valueType");
            assertEquals(r.targetAddress(), g.targetAddress(), "routes[" + i + "].targetAddress");
            assertEquals(r.stableTargetId(), g.stableTargetId(), "routes[" + i + "].stableTargetId");
            assertEquals(r.targetOneWay(), g.targetOneWay(), "routes[" + i + "].targetOneWay");
        }
    }

    /**
     * Returns a canonical copy of the route list with non-default routes sorted by
     * {@code matchHeader + matchProperty + matchValue}, and the default route placed last.
     *
     * @param routes the route list to sort; must not be {@code null}
     * @return a new sorted list
     */
    private static List<ConsumerEntry.RouteEntry> sortRoutes(List<ConsumerEntry.RouteEntry> routes) {
        return routes.stream()
                .sorted((a, b) -> {
                    // Default handler always last
                    if (a.defaultHandler() != b.defaultHandler()) {
                        return a.defaultHandler() ? 1 : -1;
                    }
                    String keyA = a.matchHeader() + "|" + a.matchProperty() + "|" + a.matchValue();
                    String keyB = b.matchHeader() + "|" + b.matchProperty() + "|" + b.matchValue();
                    return keyA.compareTo(keyB);
                })
                .toList();
    }

    // --- Model 3 parity ---

    /**
     * Parity tests for Model-3 ({@link KafkaListener} routing interface).
     */
    @Nested
    @DisplayName("Model 3: router parity (generated vs reflective)")
    class Model3RouterParity {

        @Test
        @DisplayName("toRouterEntry produces the same ConsumerEntry fields as the reflective scanner")
        void routerEntryFieldsMatchReflective() {
            // Reflective path: scan ParityRouter.class (no companion → reflective processRouterClass)
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ParityRouterTargetImpl()), configParser());
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(ParityRouter.class), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one ROUTER entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.ROUTER, reflective.kind());

            // Generated path: hand-build the KafkaBindingMeta that the processor would emit for
            // ParityRouter and call toRouterEntry() directly.
            //
            // Route 1: matchHeader="event-type", matchValue="created", dispatch to
            //          ParityRouterTarget."handle-created", valueType=ParityEvent.class
            // Route 2: defaultHandler=true, no dispatch, valueType=Void.class
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "parity-router",
                    "parity.router.events",
                    "parity-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(
                            new KafkaBindingMeta.RouteMeta(
                                    "event-type",
                                    "",
                                    "created",
                                    false,
                                    ParityEvent.class,
                                    ParityRouterTarget.class,
                                    "handle-created"),
                            new KafkaBindingMeta.RouteMeta("", "", "", true, Void.class, null, null)));

            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    PARITY_SERDE,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");

            assertEntriesEqual(reflective, generated);
        }
    }

    // --- Model 4 parity ---

    /**
     * Parity tests for Model-4 ({@link KafkaRecordHandler} instance).
     */
    @Nested
    @DisplayName("Model 4: handler parity (generated vs reflective)")
    class Model4HandlerParity {

        @Test
        @DisplayName("toHandlerEntry produces the same ConsumerEntry fields as the reflective scanner")
        void handlerEntryFieldsMatchReflective() {
            // Reflective path: scan a ParityHandler instance (no companion → reflective
            // processHandlerInstance). Use an empty service registry (handler needs no target service).
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());
            ParityHandler handler = new ParityHandler();
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(handler), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one HANDLER entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.HANDLER, reflective.kind());

            // Generated path: hand-build the KafkaBindingMeta the processor would emit for
            // ParityHandler and call toHandlerEntry() with the same live handler instance.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "parity-handler",
                    "parity.handler.events",
                    "parity-handler-grp",
                    KafkaBindingMeta.Kind.HANDLER,
                    ParityEvent.class,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toHandlerEntry(
                    meta, handler, PARITY_SERDE, EMPTY_KAFKA_CONFIG, EMPTY_CONSUMER_INDEX, usedNames, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");

            assertEntriesEqual(reflective, generated);
            // Handler identity: both paths must reference the same live handler instance
            assertHandlerSame(reflective.handler(), generated.handler());
        }

        /**
         * Asserts reference equality of handler instances, with a clear diagnostic message.
         *
         * @param reflective handler from the reflective path
         * @param generated  handler from the generated path
         */
        private void assertHandlerSame(KafkaRecordHandler<?> reflective, KafkaRecordHandler<?> generated) {
            if (reflective != generated) {
                throw new AssertionError("Expected same live handler instance — reflective: " + reflective
                        + ", generated: " + generated);
            }
        }
    }

    // --- Fix 1 parity: generic payload route ---

    /**
     * Parity test for Fix 1 — a router route with a generic payload type.
     *
     * <p>The generated path must emit the erased type ({@code Envelope.class}) so that
     * {@link GeneratedBindingMetaLoader#toRouterEntry} produces the same {@link ConsumerEntry}
     * as the reflective path's {@code processRouterClass}, which uses {@code Class#forName}
     * (which also operates on erased types).
     */
    @Nested
    @DisplayName("Fix 1: generic-payload route parity (generated vs reflective)")
    class GenericPayloadRouteParity {

        /** Simple generic envelope wrapper. */
        static class Envelope<T> {
            public T payload;
        }

        /** Target service for the generic-payload router. */
        @ServiceContract(value = "generic-target")
        interface GenericTarget {

            @ServiceOperation("process")
            @OneWay
            Future<Void> process(Envelope<ParityEvent> envelope);
        }

        static class GenericTargetImpl implements GenericTarget {

            @Override
            public Future<Void> process(Envelope<ParityEvent> envelope) {
                return Future.succeededFuture();
            }
        }

        /**
         * Router with a generic payload route — no companion present so the reflective path
         * exercises {@code Class.getMethods()}.
         */
        @KafkaListener(name = "generic-router", topic = "generic.router.events", groupId = "generic-router-grp")
        interface GenericRouter {

            @KafkaHandler(matchHeader = "type", matchValue = "order")
            @DispatchTo(service = GenericTarget.class, operation = "process")
            void onOrder(Envelope<ParityEvent> event);

            @KafkaHandler(defaultHandler = true)
            void onDefault();
        }

        @Test
        @DisplayName("generic-payload route: toRouterEntry produces same ConsumerEntry as reflective scanner")
        void genericPayloadRouteParityMatchesReflective() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GenericTargetImpl()), configParser());
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(GenericRouter.class), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one ROUTER entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.ROUTER, reflective.kind());

            // The processor would emit Envelope.class (erased) for the route value type.
            // The reflective path also resolves to the erased Envelope class.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "generic-router",
                    "generic.router.events",
                    "generic-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(
                            new KafkaBindingMeta.RouteMeta(
                                    "type",
                                    "",
                                    "order",
                                    false,
                                    // Erased type — mirrors what the emitter now produces after Fix 1
                                    Envelope.class,
                                    GenericTarget.class,
                                    "process"),
                            new KafkaBindingMeta.RouteMeta("", "", "", true, Void.class, null, null)));

            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    PARITY_SERDE,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");
            assertEntriesEqual(reflective, generated);
        }
    }

    // --- Fix 2 parity: inherited @KafkaHandler router ---

    /**
     * Parity test for Fix 2 — a router that inherits a {@code @KafkaHandler} method from
     * a super-interface.
     *
     * <p>The generated path must produce the same {@link ConsumerEntry} as the reflective path
     * for the inherited route, verifying that {@code getAllMembers} scanning in the APT processor
     * and {@code Class.getMethods()} scanning in the runtime produce equivalent metas.
     */
    @Nested
    @DisplayName("Fix 2: inherited-handler router parity (generated vs reflective)")
    class InheritedHandlerRouterParity {

        /** Base interface providing the inherited handler. */
        interface BaseHandlerInterface {

            @KafkaHandler(matchHeader = "event-type", matchValue = "inherited")
            void onInherited(ParityEvent event);
        }

        /** Extended router that inherits the handler from {@link BaseHandlerInterface}. */
        @KafkaListener(name = "inherited-router", topic = "inherited.router.events", groupId = "inherited-router-grp")
        interface InheritedRouter extends BaseHandlerInterface {

            @KafkaHandler(defaultHandler = true)
            void onDefault();
        }

        @Test
        @DisplayName("inherited @KafkaHandler route: toRouterEntry produces same ConsumerEntry as reflective scanner")
        void inheritedHandlerRouteParityMatchesReflective() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(InheritedRouter.class), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one ROUTER entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.ROUTER, reflective.kind());
            assertEquals(2, reflective.routes().size(), "Expected 2 routes (inherited + default)");

            // The generated meta mirrors what the processor now emits after Fix 2:
            // the inherited route is included in the routes list.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "inherited-router",
                    "inherited.router.events",
                    "inherited-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(
                            new KafkaBindingMeta.RouteMeta(
                                    "event-type", "", "inherited", false, ParityEvent.class, null, null),
                            new KafkaBindingMeta.RouteMeta("", "", "", true, Void.class, null, null)));

            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    PARITY_SERDE,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");
            assertEntriesEqual(reflective, generated);
        }
    }

    // --- Fix 1 parity: payload-first with trailing context router ---

    /**
     * Parity test verifying that a router with {@code (SomeEvent event, MyCtx ctx)} handler
     * parameters produces matching generated and reflective {@link ConsumerEntry} instances.
     *
     * <p>After Fix 1 (payload-must-be-first enforcement), the reflective scanner reads
     * {@code params[0]} as the payload type, and the codegen emitter (after validation ensures
     * {@code params[0]} is the payload) uses {@link dev.vertique.kafka.KafkaParamClassifier} to
     * resolve the same type. Both must agree: {@code SomeEvent.class}.
     */
    @Nested
    @DisplayName("Fix 1 (parity): payload-first with trailing context — generated == reflective")
    class PayloadFirstWithTrailingContextParity {

        /** A {@code @DispatchContextValue}-annotated type usable as a trailing context parameter. */
        @dev.vertique.core.eventbus.DispatchContextValue
        static class TrailingCtx {}

        /**
         * Router with {@code onEvent(ParityEvent event, TrailingCtx ctx)}: payload first, context
         * after. No companion on classpath so the reflective path uses {@code params[0]}.
         */
        @KafkaListener(name = "payload-ctx-router", topic = "payload.ctx.events", groupId = "payload-ctx-grp")
        interface PayloadCtxRouter {

            /** Handler with payload as first param and context as second. */
            @KafkaHandler(matchHeader = "event-type", matchValue = "created")
            void onCreated(ParityEvent event, TrailingCtx ctx);

            /** Default catch-all with no params. */
            @KafkaHandler(defaultHandler = true)
            void onDefault();
        }

        @Test
        @DisplayName("router with (SomeEvent, TrailingCtx) params: both paths resolve SomeEvent as the route valueType")
        void payloadFirstWithTrailingContextMatchesReflective() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(PayloadCtxRouter.class), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one ROUTER entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.ROUTER, reflective.kind());

            // Verify the reflective path resolved the correct payload type (SomeEvent, not TrailingCtx)
            ConsumerEntry.RouteEntry createdRoute = reflective.routes().stream()
                    .filter(r -> !r.defaultHandler())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected non-default route"));
            assertEquals(
                    ParityEvent.class, createdRoute.valueType(), "Reflective path must use params[0] = ParityEvent");

            // Generated path: hand-build the meta the processor would emit after Fix-1 validation
            // ensures payload is first.  The emitter resolves the payload type from params[0] via
            // KafkaParamClassifier — which is SomeEvent (the payload), not TrailingCtx (context).
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "payload-ctx-router",
                    "payload.ctx.events",
                    "payload-ctx-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(
                            new KafkaBindingMeta.RouteMeta(
                                    "event-type", "", "created", false, ParityEvent.class, null, null),
                            new KafkaBindingMeta.RouteMeta("", "", "", true, Void.class, null, null)));

            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    PARITY_SERDE,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");
            assertEntriesEqual(reflective, generated);
        }
    }

    // --- Model 1 parity ---

    /**
     * Parity tests for Model-1 ({@code @KafkaSource} on service implementation method).
     */
    @Nested
    @DisplayName("Model 1: @KafkaSource parity (generated vs reflective)")
    class Model1SourceParity {

        @Test
        @DisplayName("toSourceEntry produces the same ConsumerEntry fields as the reflective scanner")
        void sourceEntryFieldsMatchReflective() {
            // Reflective path: scan ParitySourceServiceImpl (no companion → reflective method scan).
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ParitySourceServiceImpl()), configParser());
            List<ConsumerEntry> reflectiveEntries = scanReflective(Set.of(), Set.of(), registry);

            assertEquals(1, reflectiveEntries.size(), "Expected exactly one BINDING entry");
            ConsumerEntry reflective = reflectiveEntries.get(0);
            assertEquals(ConsumerEntry.Kind.BINDING, reflective.kind());

            // Generated path: hand-build the KafkaBindingMeta the processor would emit for
            // ParitySourceServiceImpl and call toSourceEntry() directly.
            // Binding name is derived: serviceName + "-" + operationId = "parity-source-service-consume-event"
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "", // blank → name derived from serviceName+operationId
                    "parity.source.events",
                    "parity-source-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE — resolved at runtime from ServiceMethodMeta
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE
                    "consume-event", // targetOperation matches the @ServiceOperation operationId
                    List.of());

            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(ParitySourceService.class);
            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry generated = GeneratedBindingMetaLoader.toSourceEntry(
                    meta,
                    contractEntry,
                    resolver,
                    PARITY_SERDE,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(generated, "Generated entry must not be null");

            assertEntriesEqual(reflective, generated);
        }
    }

    /**
     * Format-bearing parity: with an avro provider registered, both paths must resolve the same
     * effective value format (avro) AND the same forced worker threading model — the gate that the
     * earlier JSON-only fixtures cannot exercise.
     */
    @Nested
    @DisplayName("Format-bearing parity (avro): generated == reflective on valueFormat + threading")
    class FormatBearingParity {

        @Test
        @DisplayName("a @KafkaSource whose payload auto-detects avro resolves identically on both paths")
        void avroFormatAndThreadingMatch() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new ParitySourceServiceImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);

            // Reflective path with the avro registry.
            List<ConsumerEntry> reflectiveEntries =
                    registrar().scan(Set.of(), Set.of(), registry, resolver, AVRO_PARITY, EMPTY_KAFKA_CONFIG);
            assertEquals(1, reflectiveEntries.size());
            ConsumerEntry reflective = reflectiveEntries.get(0);

            // Generated path with the avro registry.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "",
                    "parity.source.events",
                    "parity-source-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE
                    "consume-event",
                    List.of());
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(ParitySourceService.class);
            ConsumerEntry generated = GeneratedBindingMetaLoader.toSourceEntry(
                    meta,
                    contractEntry,
                    resolver,
                    AVRO_PARITY,
                    EMPTY_KAFKA_CONFIG,
                    EMPTY_CONSUMER_INDEX,
                    usedNames(),
                    violations());

            assertNotNull(generated);
            assertEntriesEqual(reflective, generated);
            assertEquals("avro", reflective.valueFormat(), "payload must auto-detect avro");
            assertEquals(
                    io.vertx.core.ThreadingModel.WORKER,
                    reflective.config().deploymentOptions().getThreadingModel(),
                    "mayBlock avro must force worker threading");
        }
    }
}
