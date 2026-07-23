// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link KafkaConsumerRegistrar}: all four consumption models, validation
 * violations for bad configurations, cross-model duplicate name detection, strategy
 * incompatibility (RETRY/DEAD_LETTER requiring MANUAL commit), stable target id propagation,
 * binding name derivation including type segment, and exception hierarchy proofs.
 */
class KafkaConsumerRegistrarTest {

    // --- Hierarchy proofs ---

    @Test
    @DisplayName("KafkaRegistrationException is a ConfigurationException")
    void kafkaRegistrationExceptionIsConfigurationException() {
        assertTrue(ConfigurationException.class.isAssignableFrom(KafkaRegistrationException.class));
        assertInstanceOf(ConfigurationException.class, new KafkaRegistrationException(List.of("v")));
    }

    @Test
    @DisplayName("DeserializationException is a TechnicalException")
    void deserializationExceptionIsTechnicalException() {
        assertTrue(TechnicalException.class.isAssignableFrom(DeserializationException.class));
        assertInstanceOf(TechnicalException.class, new DeserializationException("bad", new RuntimeException()));
    }

    // --- Shared test event type ---

    record TestEvent(String name) {}

    // --- Model 1 fixtures: @KafkaSource on service implementation ---

    @ServiceContract(value = "test-service")
    interface TestService {
        @ServiceOperation("process-event")
        @OneWay
        Future<Void> processEvent(TestEvent event);

        @ServiceOperation("get-status")
        Future<String> getStatus(String id);
    }

    static class TestServiceImpl implements TestService {
        @KafkaSource(topic = "test.events", groupId = "test-group")
        @Override
        public Future<Void> processEvent(TestEvent event) {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> getStatus(String id) {
            return Future.succeededFuture("ok");
        }
    }

    // Model 1 with type segment on contract
    @ServiceContract(value = "typed-service", namespace = "integration")
    interface TypedService {
        @ServiceOperation("handle-event")
        @OneWay
        Future<Void> handleEvent(TestEvent event);
    }

    static class TypedServiceImpl implements TypedService {
        @KafkaSource(topic = "typed.events", groupId = "typed-group")
        @Override
        public Future<Void> handleEvent(TestEvent event) {
            return Future.succeededFuture();
        }
    }

    // Model 1 violation: @KafkaSource placed on contract interface method
    @ServiceContract(value = "bad-service")
    interface BadContractService {
        @ServiceOperation("processEvent")
        @KafkaSource(topic = "bad.topic", groupId = "bad-group")
        @OneWay
        Future<Void> processEvent(TestEvent event);
    }

    static class BadContractServiceImpl implements BadContractService {
        @Override
        public Future<Void> processEvent(TestEvent event) {
            return Future.succeededFuture();
        }
    }

    // --- Model 3 fixtures: routing interface ---

    @KafkaListener(name = "test-router", topic = "test.events", groupId = "test-group")
    interface TestRouter {
        @KafkaHandler(matchHeader = "event-type", matchValue = "created")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onCreated(TestEvent event);

        @KafkaHandler(defaultHandler = true)
        void onDefault();
    }

    // Model 3 violation: multiple defaultHandlers
    @KafkaListener(name = "bad-router", topic = "bad.topic", groupId = "bad-group")
    interface BadRouter {
        @KafkaHandler(defaultHandler = true)
        void onDefault1();

        @KafkaHandler(defaultHandler = true)
        void onDefault2();
    }

    // Model 3 violation: @KafkaHandler with both matchHeader AND matchProperty
    @KafkaListener(name = "conflicting-router", topic = "conflict.topic", groupId = "conflict-group")
    interface ConflictingRouter {
        @KafkaHandler(matchHeader = "type", matchProperty = "eventType", matchValue = "x")
        void onConflict(TestEvent event);
    }

    // Model 3: two @KafkaHandler routes share a payload type but match on distinct header values — a
    // valid, supported shape, since route selection is by header/property/default, never by payload type.
    @KafkaListener(name = "uniform-payload-router", topic = "uniform.topic", groupId = "uniform-group")
    interface UniformPayloadRouter {
        @KafkaHandler(matchHeader = "event-type", matchValue = "a")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onA(TestEvent event);

        @KafkaHandler(matchHeader = "event-type", matchValue = "b")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onB(TestEvent event); // same payload type, distinct header value — unambiguous
    }

    // Model 3 violation: two @KafkaHandler routes with the SAME header selector — the second is
    // unreachable (resolveRoute is first-match-wins), so it must be rejected.
    @KafkaListener(name = "dup-selector-router", topic = "dup.topic", groupId = "dup-group")
    interface DuplicateSelectorRouter {
        @KafkaHandler(matchHeader = "event-type", matchValue = "same")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onA(TestEvent event);

        @KafkaHandler(matchHeader = "event-type", matchValue = "same")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onB(TestEvent event); // identical header selector → unreachable
    }

    // Model 3: selectors whose name/value contain delimiter characters must NOT falsely collide —
    // ("h","a=b") and ("h=a","b") are distinct selectors and both reachable.
    @KafkaListener(name = "delim-router", topic = "delim.topic", groupId = "delim-group")
    interface DelimiterSelectorRouter {
        @KafkaHandler(matchHeader = "h", matchValue = "a=b")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onA(TestEvent event);

        @KafkaHandler(matchHeader = "h=a", matchValue = "b")
        @DispatchTo(service = TestService.class, operation = "process-event")
        void onB(TestEvent event);
    }

    // --- Model 4 fixtures: handler class ---

    @KafkaListener(name = "test-handler", topic = "test.events", groupId = "test-group", valueType = TestEvent.class)
    static class TestHandler implements KafkaRecordHandler<TestEvent> {
        @Override
        public Future<Void> handle(KafkaMessage<TestEvent> message) {
            return Future.succeededFuture();
        }
    }

    // Model 4 violation: missing @KafkaListener
    static class HandlerWithoutListener implements KafkaRecordHandler<TestEvent> {
        @Override
        public Future<Void> handle(KafkaMessage<TestEvent> message) {
            return Future.succeededFuture();
        }
    }

    // --- Model 2 fixtures: duplicate name for cross-model conflict ---

    // Used to trigger duplicate name between Model 2 and Model 1
    @ServiceContract(value = "another-service")
    interface AnotherService {
        @ServiceOperation("do-work")
        @OneWay
        Future<Void> doWork(TestEvent event);
    }

    static class AnotherServiceImpl implements AnotherService {
        @Override
        public Future<Void> doWork(TestEvent event) {
            return Future.succeededFuture();
        }
    }

    // Service whose operation payload is an avro-typed record, so a Model-2 binding with an
    // AvroPayload value type passes the payload-assignability check and builds a registry deserializer.
    @ServiceContract(value = "avro-service")
    interface AvroService {
        @ServiceOperation("consume")
        @OneWay
        Future<Void> consume(AvroPayload event);
    }

    static class AvroServiceImpl implements AvroService {
        @Override
        public Future<Void> consume(AvroPayload event) {
            return Future.succeededFuture();
        }
    }

    // --- Helpers ---

    private static KafkaConsumerRegistrar registrar() {
        return new KafkaConsumerRegistrar();
    }

    /**
     * Returns a lenient {@link ConfigParser} for test use, matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static ServiceContractRegistry buildRegistry(Object... impls) {
        return ServiceContractRegistry.build(Set.of(impls), configParser());
    }

    /** Builds a {@link ServiceTargetResolver} from the given registry via the public factory. */
    private static ServiceTargetResolver buildResolver(ServiceContractRegistry registry) {
        return ServiceTargetResolver.of(registry);
    }

    private static dev.vertique.kafka.config.KafkaConfig emptyKafkaConfig() {
        return dev.vertique.kafka.config.KafkaConfig.fromConfig(new JsonObject(), configParser());
    }

    /**
     * Convenience scan that builds a resolver from the registry and delegates to
     * {@link KafkaConsumerRegistrar#scan}.
     */
    private static List<ConsumerEntry> scan(
            KafkaConsumerRegistrar reg,
            Set<KafkaConsumerBinding<?>> bindings,
            Set<Object> handlers,
            ServiceContractRegistry serviceRegistry) {
        return reg.scan(
                bindings,
                handlers,
                serviceRegistry,
                buildResolver(serviceRegistry),
                new dev.vertique.kafka.serialization.KafkaSerdeRegistry(
                        Set.of(new dev.vertique.kafka.serialization.TestJsonSerdeProvider())),
                emptyKafkaConfig());
    }

    // --- Model 1 tests ---

    @Nested
    @DisplayName("Model 1: @KafkaSource on service implementation")
    class Model1KafkaSource {

        @Test
        @DisplayName("@KafkaSource on implementation method produces BINDING entry with correct topic")
        void kafkaSourceOnImplProducesBINDINGEntry() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());
            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            ConsumerEntry entry = entries.get(0);
            assertEquals(ConsumerEntry.Kind.BINDING, entry.kind());
            assertEquals("test.events", entry.config().topic());
            assertEquals("test-group", entry.config().groupId());
        }

        @Test
        @DisplayName("@KafkaSource binding name defaults to {serviceName}-{operationId}")
        void kafkaSourceBindingNameDefaultsToCombinedName() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());
            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            // name = serviceName + "-" + operationId = "test-service-process-event"
            assertEquals("test-service-process-event", entries.get(0).name());
        }

        @Test
        @DisplayName("@KafkaSource binding name includes type segment when present")
        void kafkaSourceBindingNameIncludesTypeWhenPresent() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TypedServiceImpl());
            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            // name = type + "-" + serviceName + "-" + operationId = "integration-typed-service-handle-event"
            assertEquals(
                    "integration-typed-service-handle-event", entries.get(0).name());
        }

        @Test
        @DisplayName("@KafkaSource populates stableTargetId from ServiceTargetResolver")
        void kafkaSourcePopulatesStableTargetId() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());
            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            // stableTargetId = {name}.{operationId} = "test-service.process-event"
            assertEquals("test-service.process-event", entries.get(0).stableTargetId());
        }

        @Test
        @DisplayName("@KafkaSource on contract interface method triggers KafkaRegistrationException")
        void kafkaSourceOnInterfaceMethodTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new BadContractServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class, () -> scan(registrar(), Set.of(), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("contract interface")),
                    "Expected violation about @KafkaSource on contract interface, got: " + ex.violations());
        }
    }

    // --- Model 2 tests ---

    @Nested
    @DisplayName("Model 2: Declarative KafkaConsumerBinding")
    class Model2Binding {

        @Test
        @DisplayName("valid binding builder produces BINDING entry with correct topic")
        void validBindingProducesBINDINGEntry() {
            // Use AnotherServiceImpl (no @KafkaSource) so only the Model 2 binding is produced
            ServiceContractRegistry serviceRegistry = buildRegistry(new AnotherServiceImpl());

            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding-2", TestEvent.class)
                    .topic("binding.topic")
                    .groupId("binding-group")
                    .dispatchTo(AnotherService.class, "do-work")
                    .build();

            List<ConsumerEntry> entries = scan(registrar(), Set.of(binding), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            assertEquals(ConsumerEntry.Kind.BINDING, entries.get(0).kind());
            assertEquals("binding.topic", entries.get(0).config().topic());
        }

        @Test
        @DisplayName("binding resolved via ServiceTargetResolver populates stableTargetId")
        void bindingPopulatesStableTargetId() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new AnotherServiceImpl());

            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("binding-stable", TestEvent.class)
                    .topic("binding.topic")
                    .groupId("binding-group")
                    .dispatchTo(AnotherService.class, "do-work")
                    .build();

            List<ConsumerEntry> entries = scan(registrar(), Set.of(binding), Set.of(), serviceRegistry);

            assertEquals(1, entries.size());
            assertEquals("another-service.do-work", entries.get(0).stableTargetId());
        }

        @Test
        @DisplayName("binding targeting unregistered service triggers violation")
        void bindingTargetingUnregisteredServiceTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new AnotherServiceImpl());

            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("bad-binding", TestEvent.class)
                    .topic("some.topic")
                    .groupId("group")
                    .dispatchTo(TestService.class, "process-event") // TestService not in registry
                    .build();

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(binding), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("not registered")),
                    "Expected violation about unregistered service, got: " + ex.violations());
        }

        @Test
        @DisplayName("binding targeting non-existent operation triggers violation")
        void bindingTargetingNonExistentOperationTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("bad-op-binding", TestEvent.class)
                    .topic("some.topic")
                    .groupId("group")
                    .dispatchTo(TestService.class, "nonExistentOperation")
                    .build();

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(binding), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("not found")),
                    "Expected violation about operation not found, got: " + ex.violations());
        }
    }

    // --- Model 3 tests ---

    @Nested
    @DisplayName("Model 3: @KafkaListener routing interface")
    class Model3Router {

        @Test
        @DisplayName("valid routing interface produces ROUTER entry with route entries")
        void validRouterProducesROUTEREntryWithRoutes() {
            // TestServiceImpl has @KafkaSource (Model 1), so the registry produces two entries total.
            // We filter for the ROUTER entry by name to verify it was produced correctly.
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(TestRouter.class), serviceRegistry);

            ConsumerEntry routerEntry = entries.stream()
                    .filter(e -> e.kind() == ConsumerEntry.Kind.ROUTER)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No ROUTER entry found in: " + entries));

            assertEquals("test-router", routerEntry.name());
            assertFalse(routerEntry.routes().isEmpty(), "Router entry should have route entries");
        }

        @Test
        @DisplayName("@DispatchTo route entry is populated with stableTargetId")
        void routeEntryPopulatesStableTargetId() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(TestRouter.class), serviceRegistry);

            ConsumerEntry routerEntry = entries.stream()
                    .filter(e -> e.kind() == ConsumerEntry.Kind.ROUTER)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No ROUTER entry found in: " + entries));

            ConsumerEntry.RouteEntry dispatchRoute = routerEntry.routes().stream()
                    .filter(r -> r.targetAddress() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No dispatching route found"));

            assertEquals("test-service.process-event", dispatchRoute.stableTargetId());
        }

        @Test
        @DisplayName("multiple defaultHandler=true methods in same router triggers violation")
        void multipleDefaultHandlersTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(), Set.of(BadRouter.class), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("defaultHandler")),
                    "Expected violation about multiple default handlers, got: " + ex.violations());
        }

        @Test
        @DisplayName("@KafkaHandler with both matchHeader and matchProperty triggers mutual exclusivity violation")
        void matchHeaderAndMatchPropertyTogetherTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(), Set.of(ConflictingRouter.class), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("multiple match conditions")),
                    "Expected violation about multiple match conditions, got: " + ex.violations());
        }

        @Test
        @DisplayName("two @KafkaHandler routes with the same payload type but distinct header values are accepted")
        void samePayloadAcrossDistinctRoutesAccepted() {
            // Route selection is by matchHeader/matchProperty/defaultHandler, never by payload type
            // (KafkaRecordDispatcher.resolveRoute), so a uniform-payload router is a valid, supported shape.
            // The reflective scanner must accept it (parity with codegen, which also imposes no such rule).
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            List<ConsumerEntry> entries =
                    scan(registrar(), Set.of(), Set.of(UniformPayloadRouter.class), serviceRegistry);

            ConsumerEntry router = entries.stream()
                    .filter(e -> e.name().equals("uniform-payload-router"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, router.routes().size(), "both same-payload routes should be retained");
        }

        @Test
        @DisplayName("two @KafkaHandler routes with the same header selector triggers duplicate-selector violation")
        void duplicateRouteSelectorTriggersViolation() {
            // resolveRoute is first-match-wins, so an identical matchHeader+matchValue makes the second route
            // unreachable — the reflective scanner must reject it (parity with codegen HandlerMatchValidator).
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(), Set.of(DuplicateSelectorRouter.class), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("route selectors must be unique")),
                    "Expected violation about duplicate route selectors, got: " + ex.violations());
        }

        @Test
        @DisplayName("selectors with delimiter characters in name/value do not falsely collide")
        void delimiterContainingSelectorsAccepted() {
            // ("h","a=b") and ("h=a","b") map to the same delimiter-joined string but are distinct selectors;
            // the structured key keeps them apart, so the scanner must accept both routes.
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            List<ConsumerEntry> entries =
                    scan(registrar(), Set.of(), Set.of(DelimiterSelectorRouter.class), serviceRegistry);

            ConsumerEntry router = entries.stream()
                    .filter(e -> e.name().equals("delim-router"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, router.routes().size(), "distinct delimiter-containing selectors must both be retained");
        }
    }

    // --- Model 4 tests ---

    @Nested
    @DisplayName("Model 4: KafkaRecordHandler instance")
    class Model4Handler {

        @Test
        @DisplayName("valid handler instance with @KafkaListener produces HANDLER entry")
        void validHandlerProducesHANDLEREntry() {
            // Use AnotherServiceImpl (no @KafkaSource) to get exactly one entry (the HANDLER)
            ServiceContractRegistry serviceRegistry = buildRegistry(new AnotherServiceImpl());

            List<ConsumerEntry> entries = scan(registrar(), Set.of(), Set.of(new TestHandler()), serviceRegistry);

            assertEquals(1, entries.size());
            ConsumerEntry entry = entries.get(0);
            assertEquals(ConsumerEntry.Kind.HANDLER, entry.kind());
            assertEquals("test-handler", entry.name());
        }

        @Test
        @DisplayName("KafkaRecordHandler missing @KafkaListener triggers violation")
        void handlerMissingKafkaListenerTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(), Set.of(new HandlerWithoutListener()), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("missing @KafkaListener")),
                    "Expected violation about missing @KafkaListener, got: " + ex.violations());
        }
    }

    // --- Cross-model validations ---

    @Nested
    @DisplayName("Cross-model validations")
    class CrossModel {

        @Test
        @DisplayName("duplicate binding names across models trigger violation")
        void duplicateNamesAcrossModelsTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl(), new AnotherServiceImpl());

            // Model 1 will produce name "test-service-process-event"
            // Model 2 binding uses same name explicitly
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder(
                            "test-service-process-event", TestEvent.class)
                    .topic("another.topic")
                    .groupId("another-group")
                    .dispatchTo(AnotherService.class, "do-work")
                    .build();

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(binding), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("Duplicate")),
                    "Expected duplicate name violation, got: " + ex.violations());
        }

        @Test
        @DisplayName("RETRY errorStrategy with AUTO commitStrategy triggers violation")
        void retryWithAutoCommitTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            // Model 2 binding with RETRY + AUTO (invalid combination)
            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("retry-auto", TestEvent.class)
                    .topic("retry.topic")
                    .groupId("retry-group")
                    .dispatchTo(TestService.class, "process-event")
                    .errorStrategy(ErrorStrategy.RETRY)
                    .commitStrategy(CommitStrategy.AUTO) // should be MANUAL
                    .build();

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(binding), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("RETRY") && v.contains("MANUAL")),
                    "Expected RETRY+AUTO violation, got: " + ex.violations());
        }

        @Test
        @DisplayName("DEAD_LETTER errorStrategy with AUTO commitStrategy triggers violation")
        void deadLetterWithAutoCommitTriggersViolation() {
            ServiceContractRegistry serviceRegistry = buildRegistry(new TestServiceImpl());

            KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("dlq-auto", TestEvent.class)
                    .topic("dlq.topic")
                    .groupId("dlq-group")
                    .dispatchTo(TestService.class, "process-event")
                    .errorStrategy(ErrorStrategy.DEAD_LETTER)
                    .commitStrategy(CommitStrategy.AUTO)
                    .build();

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(binding), Set.of(), serviceRegistry));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("DEAD_LETTER")),
                    "Expected DEAD_LETTER+AUTO violation, got: " + ex.violations());
        }

        @Test
        @DisplayName("all violations are collected in a single exception before failing")
        void allViolationsCollectedBeforeFailing() {
            // Both BadContractServiceImpl (Model 1) and HandlerWithoutListener (Model 4) have violations
            ServiceContractRegistry serviceRegistry =
                    buildRegistry(new BadContractServiceImpl(), new TestServiceImpl());

            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> scan(registrar(), Set.of(), Set.of(new HandlerWithoutListener()), serviceRegistry));

            // Should have at least one violation from Model 1 (@KafkaSource on interface)
            // and at least one from Model 4 (missing @KafkaListener)
            assertTrue(
                    ex.violations().size() >= 2,
                    "Expected multiple violations to be collected, got: " + ex.violations());
        }
    }

    // --- Registration-abort serde cleanup ---

    /** Marker auto-detected as the "avro" format by {@link CloseTrackingAvroProvider}. */
    interface FakeAvroRecord {}

    /** Avro-typed payload — auto-detected, so the framework builds a registry-backed deserializer. */
    record AvroPayload(String field) implements FakeAvroRecord {}

    /**
     * Fake "avro" provider whose built deserializer records when it is closed, so a test can prove
     * the registrar closes framework-owned deserializers when a scan aborts with violations.
     */
    static final class CloseTrackingAvroProvider implements dev.vertique.kafka.serialization.KafkaSerdeProvider {

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
        public <V> dev.vertique.kafka.serialization.KafkaSerializer<V> serializer(
                Class<V> type, JsonObject endpointConfig) {
            return (value, topic, headers) -> new byte[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> dev.vertique.kafka.serialization.KafkaDeserializer<V> deserializer(
                Class<V> type, JsonObject endpointConfig) {
            return new dev.vertique.kafka.serialization.KafkaDeserializer<>() {
                @Override
                public V deserialize(byte[] data, String topic, java.util.Map<String, String> headers) {
                    return (V) new AvroPayload(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                }

                @Override
                public void close() {
                    deserializerClosed.set(true);
                }
            };
        }
    }

    @Nested
    @DisplayName("Registration abort: framework-owned serde cleanup")
    class RegistrationAbortCleanup {

        @Test
        @DisplayName("a framework-built deserializer of a valid entry is closed when the scan aborts")
        void abortClosesFrameworkOwnedDeserializers() {
            CloseTrackingAvroProvider provider = new CloseTrackingAvroProvider();
            var serdeRegistry = new dev.vertique.kafka.serialization.KafkaSerdeRegistry(Set.of(provider));
            ServiceContractRegistry serviceRegistry = buildRegistry(new AvroServiceImpl());

            // Valid avro binding → framework builds a registry-backed (closeable) deserializer.
            KafkaConsumerBinding<AvroPayload> valid = KafkaConsumerBinding.builder("valid-avro", AvroPayload.class)
                    .topic("valid.topic")
                    .groupId("g")
                    .dispatchTo(AvroService.class, "consume")
                    .build();
            // Invalid binding (unregistered service) → forces the scan to abort with a violation.
            KafkaConsumerBinding<AvroPayload> invalid = KafkaConsumerBinding.builder("invalid-avro", AvroPayload.class)
                    .topic("invalid.topic")
                    .groupId("g")
                    .dispatchTo(TestService.class, "process-event")
                    .build();

            KafkaConsumerRegistrar reg = registrar();
            assertThrows(
                    KafkaRegistrationException.class,
                    () -> reg.scan(
                            Set.of(valid, invalid),
                            Set.of(),
                            serviceRegistry,
                            buildResolver(serviceRegistry),
                            serdeRegistry,
                            emptyKafkaConfig()));

            assertTrue(
                    provider.deserializerClosed.get(),
                    "framework-built deserializer of the valid entry must be closed on registration abort");
        }
    }

    // --- Registration-abort serde close-failure logging ---

    /**
     * Fake "avro" provider whose built deserializer throws from {@code close()} so that a test can
     * prove the registrar WARN-logs the failure instead of swallowing it.
     */
    static final class ThrowingCloseAvroProvider implements dev.vertique.kafka.serialization.KafkaSerdeProvider {

        /** The exception thrown by the deserializer's {@code close()}. */
        static final RuntimeException CLOSE_FAILURE =
                new RuntimeException("simulated serde close failure in abort path");

        @Override
        public String format() {
            return "avro";
        }

        @Override
        public boolean autoDetects(Class<?> type) {
            // Detect AvroPayload (which implements FakeAvroRecord)
            return FakeAvroRecord.class.isAssignableFrom(type);
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
        @SuppressWarnings("unchecked")
        public <V> dev.vertique.kafka.serialization.KafkaDeserializer<V> deserializer(
                Class<V> type, JsonObject endpointConfig) {
            return new dev.vertique.kafka.serialization.KafkaDeserializer<>() {
                @Override
                public V deserialize(byte[] data, String topic, java.util.Map<String, String> headers) {
                    return (V) new AvroPayload(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                }

                @Override
                public void close() {
                    throw CLOSE_FAILURE;
                }
            };
        }
    }

    /**
     * Proves that {@link KafkaConsumerRegistrar#scan} WARN-logs a deserializer {@code close()}
     * failure on the registration-abort cleanup path instead of swallowing it silently.
     *
     * <p>The test is RED before the fix (no WARN emitted — the exception is swallowed) and GREEN
     * after ({@code log.warn(..., closeFailure)} is added in
     * {@code closeFrameworkOwnedDeserializers}).
     *
     * <p>Log capture uses a Logback {@link ListAppender} attached to the
     * {@code KafkaConsumerRegistrar} class logger, mirroring the pattern used in
     * {@link KafkaConsumerValidationCloseFailureTest}.
     */
    @Nested
    @DisplayName("Registration abort: close() failure on aborting cleanup path is WARN-logged")
    class RegistrationAbortCloseFailure {

        // --- Log capture ---

        private Logger registrarLogger;
        private ListAppender<ILoggingEvent> logAppender;

        /**
         * Attaches a {@link ListAppender} to the {@code KafkaConsumerRegistrar} class logger before
         * each test so that WARN events emitted during the abort-cleanup path can be inspected.
         */
        @BeforeEach
        void attachLogAppender() {
            registrarLogger = (Logger) LoggerFactory.getLogger(KafkaConsumerRegistrar.class);
            logAppender = new ListAppender<>();
            logAppender.setContext(registrarLogger.getLoggerContext());
            logAppender.start();
            registrarLogger.addAppender(logAppender);
        }

        /**
         * Detaches and stops the {@link ListAppender} after each test to avoid interference between
         * test cases.
         */
        @AfterEach
        void detachLogAppender() {
            registrarLogger.detachAppender(logAppender);
            logAppender.stop();
        }

        /**
         * Given a valid avro binding (framework builds a deserializer whose {@code close()} throws)
         * AND an invalid binding (forces scan to abort), when {@code scan} is called, then a
         * {@link KafkaRegistrationException} is thrown (abort unchanged) AND a WARN containing the
         * consumer name is logged by {@link KafkaConsumerRegistrar}.
         */
        @Test
        @DisplayName(
                "deserializer close() failure on abort path: KafkaRegistrationException still thrown and WARN logged"
                        + " with consumer name")
        void abortCloseFailureLogsWarnAndStillThrows() {
            // Given: a provider whose deserializer's close() throws.
            ThrowingCloseAvroProvider provider = new ThrowingCloseAvroProvider();
            var serdeRegistry = new dev.vertique.kafka.serialization.KafkaSerdeRegistry(Set.of(provider));
            ServiceContractRegistry serviceRegistry = buildRegistry(new AvroServiceImpl());

            // Valid avro binding "throw-close-avro" → framework builds a framework-owned deserializer.
            // The deserializer's close() throws, triggering the defect site.
            KafkaConsumerBinding<AvroPayload> valid = KafkaConsumerBinding.builder(
                            "throw-close-avro", AvroPayload.class)
                    .topic("valid.topic")
                    .groupId("g")
                    .dispatchTo(AvroService.class, "consume")
                    .build();
            // Invalid binding (unregistered service) → forces the scan to abort with a violation,
            // which triggers closeFrameworkOwnedDeserializers on the valid entry above.
            KafkaConsumerBinding<AvroPayload> invalid = KafkaConsumerBinding.builder(
                            "throw-close-invalid", AvroPayload.class)
                    .topic("invalid.topic")
                    .groupId("g")
                    .dispatchTo(TestService.class, "process-event")
                    .build();

            KafkaConsumerRegistrar reg = registrar();

            // When: scan is called.
            // Then: KafkaRegistrationException is thrown (abort behavior unchanged).
            assertThrows(
                    KafkaRegistrationException.class,
                    () -> reg.scan(
                            Set.of(valid, invalid),
                            Set.of(),
                            serviceRegistry,
                            buildResolver(serviceRegistry),
                            serdeRegistry,
                            emptyKafkaConfig()));

            // Then: a WARN is captured by KafkaConsumerRegistrar naming the consumer
            // "throw-close-avro".
            // RED before the fix (swallowed → no WARN), GREEN after (log.warn is added).
            String capturedWarns = logAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(
                    capturedWarns.contains("throw-close-avro"),
                    "WARN must name the consumer 'throw-close-avro'; captured WARN lines:\n" + capturedWarns);
        }
    }
}
