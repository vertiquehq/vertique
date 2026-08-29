// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.vertique.services.resilience.ServiceResiliencePipelineFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for the Kafka RETRY error strategy and Model 3 header-based router dispatch.
 *
 * <p>Retry tests verify:
 * <ul>
 *   <li>A record that fails initially is redelivered by Kafka and eventually succeeds.</li>
 *   <li>After {@link RetryConfig#maxRetries()} exhausted retries, the record is skipped
 *       via the configured exhausted strategy ({@link ErrorStrategy#SKIP}).</li>
 * </ul>
 *
 * <p>Router dispatch tests verify:
 * <ul>
 *   <li>Records with a matching {@code event-type} header are routed to the correct handler.</li>
 *   <li>Records with an unknown header land on the default handler route.</li>
 *   <li>Records with no matching route and no default handler are skipped silently.</li>
 * </ul>
 *
 * <p>Each retry test uses a distinct topic and consumer binding to prevent cross-test
 * interference from Kafka-native redelivery. All verticles are deployed once in
 * {@link #setUp} and torn down in {@link #tearDown}.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 120, unit = TimeUnit.SECONDS)
public class KafkaRetryAndRouterIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    // --- Shared collectors ---

    /**
     * Events that eventually succeeded after retries (retry-succeed scenario).
     * Keyed by event ID for safe cross-test assertions.
     */
    static final ConcurrentHashMap<String, TestEvent> retrySucceededById = new ConcurrentHashMap<>();

    /**
     * Events that eventually succeeded in the exhaustion scenario (normal records after
     * the always-fail record is skipped).
     */
    static final ConcurrentHashMap<String, TestEvent> exhaustSucceededById = new ConcurrentHashMap<>();

    /** Events received by the type-A router handler. */
    static final List<TestEvent> routerTypeAReceived = Collections.synchronizedList(new ArrayList<>());

    /** Events received by the type-B router handler. */
    static final List<TestEvent> routerTypeBReceived = Collections.synchronizedList(new ArrayList<>());

    /** Events received by the router default handler. */
    static final List<TestEvent> routerDefaultReceived = Collections.synchronizedList(new ArrayList<>());

    /**
     * Per-event attempt counter keyed by event ID. Each retry test scenario tracks its own
     * events so attempt counts do not interfere across tests.
     */
    static final ConcurrentHashMap<String, AtomicInteger> perEventAttempts = new ConcurrentHashMap<>();

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    // --- Service contracts for retry-succeed test ---

    /**
     * Service contract for the retry-and-succeed scenario. Uses request-reply semantics
     * so failures propagate back to the Kafka consumer.
     */
    @ServiceContract(value = "retry-succeed-service")
    interface RetrySucceedService {

        /**
         * Fails on the first attempt for events named {@code "retry-then-succeed"}, succeeds
         * on subsequent attempts. Adds successfully processed events to
         * {@link #retrySucceededById}.
         *
         * @param event the event to process
         * @return a failed future on the first attempt; succeeded thereafter
         */
        @ServiceOperation("processEvent")
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Implementation of {@link RetrySucceedService}.
     */
    static class RetrySucceedServiceImpl implements RetrySucceedService {

        @Override
        public Future<Void> processEvent(TestEvent event) {
            int attempt = perEventAttempts
                    .computeIfAbsent(event.id(), k -> new AtomicInteger())
                    .incrementAndGet();
            if ("retry-then-succeed".equals(event.name()) && attempt <= 1) {
                return Future.failedFuture(new RuntimeException("transient failure on first attempt"));
            }
            retrySucceededById.put(event.id(), event);
            return Future.succeededFuture();
        }
    }

    // --- Service contracts for retry-exhaustion test ---

    /**
     * Service contract for the retry-exhaustion scenario. Uses request-reply semantics
     * so failures propagate back to the Kafka consumer.
     */
    @ServiceContract(value = "retry-exhaust-service")
    interface RetryExhaustService {

        /**
         * Always fails for events named {@code "always-fail"}. Other events succeed and
         * are added to {@link #exhaustSucceededById}.
         *
         * @param event the event to process
         * @return a permanently failed future for always-fail events; succeeded otherwise
         */
        @ServiceOperation("processEvent")
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Implementation of {@link RetryExhaustService}.
     */
    static class RetryExhaustServiceImpl implements RetryExhaustService {

        @Override
        public Future<Void> processEvent(TestEvent event) {
            perEventAttempts
                    .computeIfAbsent(event.id(), k -> new AtomicInteger())
                    .incrementAndGet();
            if ("always-fail".equals(event.name())) {
                return Future.failedFuture(new RuntimeException("permanent failure"));
            }
            exhaustSucceededById.put(event.id(), event);
            return Future.succeededFuture();
        }
    }

    // --- Service contracts for router tests ---

    /**
     * Service contract that handles type-A events arriving via header-based routing.
     */
    @ServiceContract(value = "router-type-a-service")
    interface RouterTypeAService {

        /**
         * Processes a type-A event routed from the Kafka router consumer.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("handleTypeA")
        @OneWay
        Future<Void> handleTypeA(TestEvent event);
    }

    /**
     * Service contract that handles type-B events arriving via header-based routing.
     */
    @ServiceContract(value = "router-type-b-service")
    interface RouterTypeBService {

        /**
         * Processes a type-B event routed from the Kafka router consumer.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("handleTypeB")
        @OneWay
        Future<Void> handleTypeB(TestEvent event);
    }

    /**
     * Service contract that handles default-route events in the router consumer.
     */
    @ServiceContract(value = "router-default-service")
    interface RouterDefaultService {

        /**
         * Processes events that did not match any specific header-based route.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("handleDefault")
        @OneWay
        Future<Void> handleDefault(TestEvent event);
    }

    // --- Service implementations ---

    /** Appends type-A events to {@link #routerTypeAReceived}. */
    static class RouterTypeAServiceImpl implements RouterTypeAService {

        @Override
        public Future<Void> handleTypeA(TestEvent event) {
            routerTypeAReceived.add(event);
            return Future.succeededFuture();
        }
    }

    /** Appends type-B events to {@link #routerTypeBReceived}. */
    static class RouterTypeBServiceImpl implements RouterTypeBService {

        @Override
        public Future<Void> handleTypeB(TestEvent event) {
            routerTypeBReceived.add(event);
            return Future.succeededFuture();
        }
    }

    /** Appends default-route events to {@link #routerDefaultReceived}. */
    static class RouterDefaultServiceImpl implements RouterDefaultService {

        @Override
        public Future<Void> handleDefault(TestEvent event) {
            routerDefaultReceived.add(event);
            return Future.succeededFuture();
        }
    }

    // --- Model 3: header-based router interface (with default handler) ---

    /**
     * Routing interface for the header-based router consumer. Routes {@code event-type: type-a}
     * to {@link RouterTypeAService#handleTypeA}, {@code type-b} to
     * {@link RouterTypeBService#handleTypeB}, and everything else to
     * {@link RouterDefaultService#handleDefault}.
     */
    @KafkaListener(name = "router-header-test", topic = "it.router.events", groupId = "it-router-group")
    interface TestRouter {

        /**
         * Handles type-A events.
         *
         * @param event the deserialized event
         */
        @KafkaHandler(matchHeader = "event-type", matchValue = "type-a")
        @DispatchTo(service = RouterTypeAService.class, operation = "handleTypeA")
        void onTypeA(TestEvent event);

        /**
         * Handles type-B events.
         *
         * @param event the deserialized event
         */
        @KafkaHandler(matchHeader = "event-type", matchValue = "type-b")
        @DispatchTo(service = RouterTypeBService.class, operation = "handleTypeB")
        void onTypeB(TestEvent event);

        /**
         * Default handler for records that did not match any specific route.
         *
         * @param event the deserialized event
         */
        @KafkaHandler(defaultHandler = true)
        @DispatchTo(service = RouterDefaultService.class, operation = "handleDefault")
        void onDefault(TestEvent event);
    }

    /**
     * Router interface for the no-default-handler test. Only routes {@code type-x}; records
     * with any other header are silently skipped.
     */
    @KafkaListener(
            name = "router-no-default-test",
            topic = "it.router.nodefault.events",
            groupId = "it-router-nodefault-group")
    interface NoDefaultRouter {

        /**
         * Handles type-X events only; no default handler so unmatched records are dropped.
         *
         * @param event the deserialized event
         */
        @KafkaHandler(matchHeader = "event-type", matchValue = "type-x")
        @DispatchTo(service = RouterTypeAService.class, operation = "handleTypeA")
        void onTypeX(TestEvent event);
    }

    // --- Lifecycle ---

    /**
     * Starts Kafka, wires up all service and consumer verticles, and waits for consumer
     * group assignment before any tests run.
     *
     * <p>Each retry scenario has its own topic, consumer binding, and service contract to
     * prevent Kafka-native redelivery from one scenario interfering with another.
     *
     * @param vertx the Vert.x instance injected by the JUnit 5 extension
     * @param ctx   the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        retrySucceededById.clear();
        exhaustSucceededById.clear();
        routerTypeAReceived.clear();
        routerTypeBReceived.clear();
        routerDefaultReceived.clear();
        perEventAttempts.clear();

        // --- Event bus codecs (idempotent) ---
        tryRegisterCodec(vertx, "dispatch.envelope");
        tryRegisterCodec(vertx, "dispatch.result");

        // --- Base config (bootstrap.servers only; per-consumer overrides added below) ---
        JsonObject config = new JsonObject()
                .put(
                        "kafka",
                        new JsonObject()
                                .put("bootstrap.servers", kafka.getBootstrapServers())
                                .put(
                                        "consumers",
                                        new JsonObject()
                                                // Retry-succeed scenario: 2 max retries, short backoff, SKIP on
                                                // exhaustion
                                                .put(
                                                        "retry-succeed-binding",
                                                        new JsonObject()
                                                                .put("errorStrategy", "RETRY")
                                                                .put("commitStrategy", "MANUAL")
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 2)
                                                                                .put("backoffMs", 300L)
                                                                                .put("backoffMultiplier", 1.0)
                                                                                .put("exhaustedStrategy", "SKIP")))
                                                // Retry-exhaust scenario: 2 max retries, short backoff, SKIP on
                                                // exhaustion
                                                .put(
                                                        "retry-exhaust-binding",
                                                        new JsonObject()
                                                                .put("errorStrategy", "RETRY")
                                                                .put("commitStrategy", "MANUAL")
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 2)
                                                                                .put("backoffMs", 300L)
                                                                                .put("backoffMultiplier", 1.0)
                                                                                .put("exhaustedStrategy", "SKIP")))));

        // --- Build service registries ---
        RetrySucceedServiceImpl retrySucceedImpl = new RetrySucceedServiceImpl();
        RetryExhaustServiceImpl retryExhaustImpl = new RetryExhaustServiceImpl();
        RouterTypeAServiceImpl typeAImpl = new RouterTypeAServiceImpl();
        RouterTypeBServiceImpl typeBImpl = new RouterTypeBServiceImpl();
        RouterDefaultServiceImpl defaultImpl = new RouterDefaultServiceImpl();

        ServiceContractRegistry retrySucceedRegistry =
                ServiceContractRegistry.build(Set.of(retrySucceedImpl), configParser());
        ServiceContractRegistry retryExhaustRegistry =
                ServiceContractRegistry.build(Set.of(retryExhaustImpl), configParser());
        ServiceContractRegistry routerRegistry =
                ServiceContractRegistry.build(Set.of(typeAImpl, typeBImpl, defaultImpl), configParser());

        // --- Retry-succeed consumer binding (isolated topic + group) ---
        KafkaConsumerBinding<TestEvent> retrySucceedBinding = KafkaConsumerBinding.builder(
                        "retry-succeed-binding", TestEvent.class)
                .topic("it.retry.succeed.events")
                .groupId("it-retry-succeed-group")
                .dispatchTo(RetrySucceedService.class, "processEvent")
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.RETRY)
                .build();
        KafkaConsumerRegistry retrySucceedConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(retrySucceedBinding),
                Set.of(),
                retrySucceedRegistry,
                ServiceTargetResolver.of(retrySucceedRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Retry-exhaust consumer binding (isolated topic + group) ---
        KafkaConsumerBinding<TestEvent> retryExhaustBinding = KafkaConsumerBinding.builder(
                        "retry-exhaust-binding", TestEvent.class)
                .topic("it.retry.exhaust.events")
                .groupId("it-retry-exhaust-group")
                .dispatchTo(RetryExhaustService.class, "processEvent")
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.RETRY)
                .build();
        KafkaConsumerRegistry retryExhaustConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(retryExhaustBinding),
                Set.of(),
                retryExhaustRegistry,
                ServiceTargetResolver.of(retryExhaustRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Router consumer registry (Model 3) ---
        KafkaConsumerRegistry routerConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(),
                Set.of(TestRouter.class, NoDefaultRouter.class),
                routerRegistry,
                ServiceTargetResolver.of(routerRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Deploy service verticles ---
        ServiceExceptionMapper exceptionMapper = new ServiceExceptionMapper();
        ServiceResiliencePipelineFactory resiliencePipelineFactory =
                KafkaTestSupport.resiliencePipelineFactory(vertx, config);

        ServiceVerticle<RetrySucceedService> retrySucceedVerticle = new ServiceVerticle<>(
                retrySucceedRegistry.resolve(RetrySucceedService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);
        ServiceVerticle<RetryExhaustService> retryExhaustVerticle = new ServiceVerticle<>(
                retryExhaustRegistry.resolve(RetryExhaustService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);
        ServiceVerticle<RouterTypeAService> typeAVerticle = new ServiceVerticle<>(
                routerRegistry.resolve(RouterTypeAService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);
        ServiceVerticle<RouterTypeBService> typeBVerticle = new ServiceVerticle<>(
                routerRegistry.resolve(RouterTypeBService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);
        ServiceVerticle<RouterDefaultService> defaultVerticle = new ServiceVerticle<>(
                routerRegistry.resolve(RouterDefaultService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);

        // --- Deploy Kafka consumer verticles ---
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        List<Future<?>> deployments = new ArrayList<>();
        deployments.add(vertx.deployVerticle(retrySucceedVerticle));
        deployments.add(vertx.deployVerticle(retryExhaustVerticle));
        deployments.add(vertx.deployVerticle(typeAVerticle));
        deployments.add(vertx.deployVerticle(typeBVerticle));
        deployments.add(vertx.deployVerticle(defaultVerticle));

        ServiceTargetResolver retrySucceedResolver = ServiceTargetResolver.of(retrySucceedRegistry);
        ServiceTargetResolver retryExhaustResolver = ServiceTargetResolver.of(retryExhaustRegistry);
        ServiceTargetResolver routerResolver = ServiceTargetResolver.of(routerRegistry);

        for (ConsumerEntry entry : retrySucceedConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        retrySucceedResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        for (ConsumerEntry entry : retryExhaustConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        retryExhaustResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        for (ConsumerEntry entry : routerConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        routerResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        Future.join(deployments).onComplete(ctx.succeeding(cf -> vertx.setTimer(3_000L, id -> ctx.completeNow())));
    }

    // --- Retry tests ---

    @Test
    @DisplayName("should retry via Kafka redelivery and eventually succeed")
    void shouldRetryViaKafkaRedelivery(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        String eventId = "retry-succeed-001";

        // This event fails on the first attempt (attempt=1) and succeeds on second (attempt=2)
        TestEvent event = new TestEvent(eventId, "retry-then-succeed");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.retry.succeed.events", "retry-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Wait for eventual success; allow time for backoff (300ms) + redelivery overhead
        waitFor(() -> retrySucceededById.containsKey(eventId), 20_000);

        assertTrue(retrySucceededById.containsKey(eventId), "Event should eventually succeed after one retry");
        AtomicInteger counter = perEventAttempts.get(eventId);
        assertTrue(counter != null && counter.get() >= 2, "Service should have been invoked at least twice");
    }

    @Test
    @DisplayName("should skip record after max retries are exhausted (SKIP exhausted strategy)")
    void shouldApplyExhaustedStrategyAfterMaxRetries(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        String exhaustId = "exhaust-always-001";
        String normalId = "exhaust-normal-001";

        // "always-fail" never succeeds; maxRetries=2 so it will be skipped after 3 total attempts
        TestEvent alwaysFail = new TestEvent(exhaustId, "always-fail");
        byte[] failBytes = DatabindCodec.mapper().writeValueAsBytes(alwaysFail);
        producerFactory
                .send("it.retry.exhaust.events", "exhaust-key-1", failBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a normal record after the exhausted one to confirm the consumer continued
        TestEvent normal = new TestEvent(normalId, "normal-after-exhaustion");
        byte[] normalBytes = DatabindCodec.mapper().writeValueAsBytes(normal);
        producerFactory
                .send("it.retry.exhaust.events", "exhaust-key-normal", normalBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Wait for at least 3 total attempts (initial + 2 retries = maxRetries) and the normal record
        waitFor(
                () -> {
                    AtomicInteger counter = perEventAttempts.get(exhaustId);
                    return counter != null && counter.get() >= 3;
                },
                25_000);
        waitFor(() -> exhaustSucceededById.containsKey(normalId), 15_000);

        AtomicInteger exhaustCounter = perEventAttempts.get(exhaustId);
        assertTrue(
                exhaustCounter != null && exhaustCounter.get() >= 3,
                "Should have attempted at least 3 times (1 initial + 2 retries = max 3 total)");
        assertFalse(
                retrySucceededById.containsKey(exhaustId) || exhaustSucceededById.containsKey(exhaustId),
                "Permanently failing record should never appear in succeeded collections");
        assertTrue(exhaustSucceededById.containsKey(normalId), "Consumer should continue after retry exhaustion");
    }

    // --- Router dispatch tests ---

    @Test
    @DisplayName("should route messages to correct handlers based on event-type header")
    void shouldRouteByHeader(Vertx vertx) throws Exception {
        routerTypeAReceived.clear();
        routerTypeBReceived.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent typeAEvent = new TestEvent("route-a-001", "type-a-event");
        TestEvent typeBEvent = new TestEvent("route-b-001", "type-b-event");

        byte[] typeABytes = DatabindCodec.mapper().writeValueAsBytes(typeAEvent);
        byte[] typeBBytes = DatabindCodec.mapper().writeValueAsBytes(typeBEvent);

        producerFactory
                .send("it.router.events", "route-key-a", typeABytes, Map.of("event-type", "type-a"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();
        producerFactory
                .send("it.router.events", "route-key-b", typeBBytes, Map.of("event-type", "type-b"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> routerTypeAReceived.stream().anyMatch(e -> "route-a-001".equals(e.id())), 10_000);
        waitFor(() -> routerTypeBReceived.stream().anyMatch(e -> "route-b-001".equals(e.id())), 10_000);

        assertTrue(
                routerTypeAReceived.stream().anyMatch(e -> "route-a-001".equals(e.id())),
                "Type-A event should be routed to RouterTypeAService.handleTypeA");
        assertTrue(
                routerTypeBReceived.stream().anyMatch(e -> "route-b-001".equals(e.id())),
                "Type-B event should be routed to RouterTypeBService.handleTypeB");
        assertFalse(
                routerTypeAReceived.stream().anyMatch(e -> "route-b-001".equals(e.id())),
                "Type-B event should NOT appear in type-A handler");
        assertFalse(
                routerTypeBReceived.stream().anyMatch(e -> "route-a-001".equals(e.id())),
                "Type-A event should NOT appear in type-B handler");
    }

    @Test
    @DisplayName("should route unmatched messages to the default handler")
    void shouldHandleDefaultRoute(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce a record with an unknown header value — should fall through to default handler
        TestEvent unknownEvent = new TestEvent("route-default-001", "unknown-type-event");
        byte[] unknownBytes = DatabindCodec.mapper().writeValueAsBytes(unknownEvent);
        producerFactory
                .send("it.router.events", "route-key-unknown", unknownBytes, Map.of("event-type", "unknown-type"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> routerDefaultReceived.stream().anyMatch(e -> "route-default-001".equals(e.id())), 10_000);

        assertTrue(
                routerDefaultReceived.stream().anyMatch(e -> "route-default-001".equals(e.id())),
                "Unmatched event should be routed to the default handler");
    }

    @Test
    @DisplayName("should silently skip records with no matching route and no default handler")
    void shouldSkipWhenNoRouteAndNoDefault(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce a record with header "event-type: type-y" — NoDefaultRouter has no matching
        // route and no default handler so this should be silently skipped
        TestEvent unmatchedEvent = new TestEvent("no-route-001", "unmatched-event");
        byte[] unmatchedBytes = DatabindCodec.mapper().writeValueAsBytes(unmatchedEvent);
        producerFactory
                .send("it.router.nodefault.events", "no-route-key", unmatchedBytes, Map.of("event-type", "type-y"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a type-x record to verify the consumer is alive and routing correctly
        TestEvent typeXEvent = new TestEvent("no-route-typex-001", "type-x-after-skip");
        byte[] typeXBytes = DatabindCodec.mapper().writeValueAsBytes(typeXEvent);
        producerFactory
                .send("it.router.nodefault.events", "typex-key", typeXBytes, Map.of("event-type", "type-x"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> routerTypeAReceived.stream().anyMatch(e -> "no-route-typex-001".equals(e.id())), 10_000);

        assertFalse(
                routerTypeAReceived.stream().anyMatch(e -> "no-route-001".equals(e.id())),
                "Record with no matching route should be silently skipped");
        assertTrue(
                routerTypeAReceived.stream().anyMatch(e -> "no-route-typex-001".equals(e.id())),
                "Consumer should continue processing after skipping unroutable record");
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
     * Polls the given condition until it becomes {@code true} or {@code timeoutMs} elapses.
     *
     * @param condition the condition to wait for
     * @param timeoutMs maximum wait time in milliseconds
     * @throws InterruptedException if the polling thread is interrupted
     */
    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }
}
