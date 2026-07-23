// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.ServiceVerticle;
import dev.vertique.services.policy.PolicyChainBuilder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for {@link KafkaConsumerVerticle} and {@link KafkaConsumerDeploymentManager}.
 *
 * <p>Verifies end-to-end flow: produce a Kafka message → consumer verticle receives it →
 * event bus dispatch → service handler processes it. Uses a real Kafka broker via Testcontainers.
 *
 * <p>Two scenarios are covered:
 * <ul>
 *   <li>Normal dispatch: every produced message is received and processed by the service.</li>
 *   <li>Filter bypass: records that do not satisfy a {@link KafkaRecordFilter} are silently
 *       skipped and never dispatched to the service.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 60, unit = TimeUnit.SECONDS)
public class KafkaConsumerIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    // --- Shared test state ---

    /** Synchronised list that the test service implementation appends events to. */
    static final List<TestEvent> received = Collections.synchronizedList(new ArrayList<>());

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    /**
     * Service contract annotated with {@link ServiceContract} so that the service registry
     * can derive event bus addresses automatically.
     */
    @ServiceContract(value = "kafka-it-service")
    interface TestService {
        /**
         * Processes an incoming event. Declared {@link OneWay} so the Kafka consumer
         * uses fire-and-forget dispatch (no reply expected).
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("processEvent")
        @OneWay
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Implementation of {@link TestService} that appends each received event to
     * the shared {@link #received} list and is annotated with {@link KafkaSource}
     * so the registrar auto-creates a consumer binding.
     */
    static class TestServiceImpl implements TestService {

        @KafkaSource(topic = "it.test.events", groupId = "it-test-group")
        @Override
        public Future<Void> processEvent(TestEvent event) {
            received.add(event);
            return Future.succeededFuture();
        }
    }

    /**
     * Service contract for the filtered-consumer scenario. A separate contract is used
     * so that its consumer binding (and event bus address) do not conflict with
     * {@link TestService}.
     */
    @ServiceContract(value = "kafka-it-filtered-service")
    interface FilteredService {
        /**
         * Processes an event that passed a pre-deserialization header filter.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("processEvent")
        @OneWay
        Future<Void> processEvent(TestEvent event);
    }

    /** Events received through the filtered binding. */
    static final List<TestEvent> filteredReceived = Collections.synchronizedList(new ArrayList<>());

    /**
     * Implementation of {@link FilteredService}. The {@link KafkaSource} binding is
     * supplemented by a {@link KafkaConsumerBinding} in {@link #setUp} that attaches
     * a header filter.
     */
    static class FilteredServiceImpl implements FilteredService {

        @Override
        public Future<Void> processEvent(TestEvent event) {
            filteredReceived.add(event);
            return Future.succeededFuture();
        }
    }

    // --- Lifecycle ---

    /**
     * Starts Kafka, registers event bus codecs, builds service and consumer registries,
     * deploys all verticles and awaits completion before any tests run.
     *
     * @param vertx the Vert.x instance injected by the JUnit 5 extension
     * @param ctx   the test context used to signal completion or failure
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        received.clear();
        filteredReceived.clear();

        // --- Register local event bus codecs (idempotent) ---
        tryRegisterCodec(vertx, "dispatch.envelope");
        tryRegisterCodec(vertx, "dispatch.result");

        // --- Build service registry for TestService (Model 1 via @KafkaSource) ---
        TestServiceImpl testImpl = new TestServiceImpl();
        ServiceContractRegistry testServiceRegistry = ServiceContractRegistry.build(Set.of(testImpl), configParser());

        // --- Build service registry for FilteredService (Model 2 via KafkaConsumerBinding) ---
        FilteredServiceImpl filteredImpl = new FilteredServiceImpl();
        ServiceContractRegistry filteredServiceRegistry =
                ServiceContractRegistry.build(Set.of(filteredImpl), configParser());

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));

        // --- Build consumer registry for Model 1 (@KafkaSource-driven) ---
        KafkaConsumerRegistry consumerRegistry = KafkaConsumerRegistry.build(
                Set.of(),
                Set.of(),
                testServiceRegistry,
                ServiceTargetResolver.of(testServiceRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Build consumer binding (Model 2) for the filtered scenario ---
        KafkaConsumerBinding<TestEvent> filteredBinding = KafkaConsumerBinding.builder(
                        "filtered-event-binding", TestEvent.class)
                .topic("it.filtered.events")
                .groupId("it-filtered-group")
                .dispatchTo(FilteredService.class, "processEvent")
                .filter(KafkaRecordFilter.headerEquals("x-event-type", "accepted"))
                .build();
        KafkaConsumerRegistry filteredRegistry = KafkaConsumerRegistry.build(
                Set.of(filteredBinding),
                Set.of(),
                filteredServiceRegistry,
                ServiceTargetResolver.of(filteredServiceRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Deploy service verticles ---
        ServiceExceptionMapper exceptionMapper = new ServiceExceptionMapper();
        PolicyChainBuilder policyChain = KafkaTestSupport.policyChainBuilder(vertx, config);

        ServiceVerticle<TestService> serviceVerticle = new ServiceVerticle<>(
                testServiceRegistry.resolve(TestService.class), exceptionMapper, List.of(), policyChain, null);

        ServiceVerticle<FilteredService> filteredServiceVerticle = new ServiceVerticle<>(
                filteredServiceRegistry.resolve(FilteredService.class), exceptionMapper, List.of(), policyChain, null);

        // --- Deploy Kafka consumer verticles directly (bypassing VerticleDeployer) ---
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        List<Future<?>> deployments = new ArrayList<>();
        deployments.add(vertx.deployVerticle(serviceVerticle));
        deployments.add(vertx.deployVerticle(filteredServiceVerticle));

        ServiceTargetResolver testResolver = ServiceTargetResolver.of(testServiceRegistry);
        ServiceTargetResolver filteredResolver = ServiceTargetResolver.of(filteredServiceRegistry);

        for (ConsumerEntry entry : consumerRegistry.entries()) {
            if (entry.config().enabled()) {
                KafkaConsumerVerticle consumerVerticle = new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        testResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry());
                deployments.add(vertx.deployVerticle(consumerVerticle));
            }
        }

        for (ConsumerEntry entry : filteredRegistry.entries()) {
            if (entry.config().enabled()) {
                KafkaConsumerVerticle consumerVerticle = new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        filteredResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry());
                deployments.add(vertx.deployVerticle(consumerVerticle));
            }
        }

        // After all verticles are deployed, allow a brief settle period for consumer group
        // rebalancing and partition assignment before tests start producing messages.
        Future.join(deployments).onComplete(ctx.succeeding(cf -> vertx.setTimer(3_000L, id -> ctx.completeNow())));
    }

    // --- Tests ---

    @Test
    @DisplayName("should dispatch Kafka message to service handler via event bus")
    void shouldDispatchKafkaMessageToService(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent event = new TestEvent("evt-001", "integration-test");
        byte[] bytes = io.vertx.core.json.jackson.DatabindCodec.mapper().writeValueAsBytes(event);

        producerFactory
                .send("it.test.events", "key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(received, 1, 10_000);

        assertEquals(1, received.size(), "Service handler should have received exactly one event");
        assertEquals("evt-001", received.get(0).id());
        assertEquals("integration-test", received.get(0).name());
    }

    @Test
    @DisplayName("should skip messages that do not satisfy the header filter")
    void shouldSkipFilteredMessages(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent accepted = new TestEvent("evt-accepted", "accepted-event");
        TestEvent rejected = new TestEvent("evt-rejected", "rejected-event");

        byte[] acceptedBytes = io.vertx.core.json.jackson.DatabindCodec.mapper().writeValueAsBytes(accepted);
        byte[] rejectedBytes = io.vertx.core.json.jackson.DatabindCodec.mapper().writeValueAsBytes(rejected);

        // Produce the rejected message first (no x-event-type header → filtered out)
        producerFactory
                .send("it.filtered.events", "key-rejected", rejectedBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce the accepted message (with x-event-type: accepted header)
        producerFactory
                .send("it.filtered.events", "key-accepted", acceptedBytes, Map.of("x-event-type", "accepted"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(filteredReceived, 1, 10_000);

        // Only the accepted message should have been dispatched
        assertEquals(1, filteredReceived.size(), "Filtered service should have received exactly one event");
        assertEquals("evt-accepted", filteredReceived.get(0).id());
    }

    @Test
    @DisplayName("should handle multiple messages in order")
    void shouldHandleMultipleMessages(Vertx vertx) throws Exception {
        // Clear any events from previous tests
        received.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("batch-" + i, "batch-event-" + i);
            byte[] bytes = io.vertx.core.json.jackson.DatabindCodec.mapper().writeValueAsBytes(event);
            producerFactory
                    .send("it.test.events", "batch-key-" + i, bytes, null)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        }

        waitForMessages(received, messageCount, 15_000);

        assertTrue(
                received.size() >= messageCount,
                "Service handler should have received at least " + messageCount + " events, got " + received.size());
    }

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for test use, matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Registers a local event bus codec silently ignoring duplicate registration errors.
     *
     * @param vertx the Vert.x instance
     * @param name  the codec name
     */
    private static void tryRegisterCodec(Vertx vertx, String name) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
        } catch (IllegalStateException ignored) {
            // Already registered — idempotent
        }
    }

    /**
     * Polls the given list until it contains at least {@code expected} elements or the
     * {@code timeoutMs} deadline is exceeded. Uses {@link Thread#sleep} in 100 ms intervals
     * to avoid busy-waiting.
     *
     * @param list      the list to poll
     * @param expected  the minimum expected element count
     * @param timeoutMs maximum wait time in milliseconds
     * @throws InterruptedException if the polling thread is interrupted
     */
    private static void waitForMessages(List<?> list, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (list.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }
}
