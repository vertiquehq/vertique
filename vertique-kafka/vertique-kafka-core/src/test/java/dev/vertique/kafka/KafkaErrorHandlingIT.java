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
import dev.vertique.services.resilience.ServiceResiliencePipelineFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for Kafka consumer error handling strategies.
 *
 * <p>Verifies the following error scenarios with a real Kafka broker:
 * <ul>
 *   <li>{@link ErrorStrategy#SKIP} — malformed JSON is logged and skipped; the consumer
 *       continues to process subsequent valid messages.</li>
 *   <li>{@link ErrorStrategy#DEAD_LETTER} — records that cause service handler failures are
 *       forwarded to the configured dead-letter topic; the original topic consumer
 *       continues uninterrupted.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 90, unit = TimeUnit.SECONDS)
public class KafkaErrorHandlingIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    // --- Shared test state ---

    /** Events received by the skip-strategy service handler. */
    static final List<TestEvent> skipReceived = Collections.synchronizedList(new ArrayList<>());

    /** Events received by the dead-letter service handler. */
    static final List<TestEvent> dlqHandlerReceived = Collections.synchronizedList(new ArrayList<>());

    /** Number of times the DLQ-triggering service handler has been invoked (including failures). */
    static final AtomicInteger dlqHandlerInvocations = new AtomicInteger(0);

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    // --- Service contracts for skip scenario ---

    /**
     * Service contract for the {@link ErrorStrategy#SKIP} error handling scenario.
     */
    @ServiceContract(value = "kafka-skip-service")
    interface SkipService {
        /**
         * Processes an event; the consumer binding uses {@link ErrorStrategy#SKIP} so that
         * deserialization failures are silently dropped.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("processEvent")
        @OneWay
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Implementation of {@link SkipService} that appends events to {@link #skipReceived}.
     */
    static class SkipServiceImpl implements SkipService {

        @KafkaSource(topic = "it.skip.events", groupId = "it-skip-group", errorStrategy = ErrorStrategy.SKIP)
        @Override
        public Future<Void> processEvent(TestEvent event) {
            skipReceived.add(event);
            return Future.succeededFuture();
        }
    }

    // --- Service contracts for dead-letter scenario ---

    /**
     * Service contract for the {@link ErrorStrategy#DEAD_LETTER} scenario.
     * The consumer binding uses DEAD_LETTER + MANUAL commit so that failed records are
     * forwarded to a DLQ topic before the offset advances.
     */
    @ServiceContract(value = "kafka-dlq-service")
    interface DlqService {
        /**
         * Processes an event using request-reply semantics so that handler failures propagate
         * back to the Kafka consumer and trigger dead-letter routing.
         *
         * <p>Events with ID starting with {@code "fail-"} cause an intentional failure in the
         * implementation, which the framework routes to the configured dead-letter topic.
         *
         * @param event the event to process
         * @return a future that completes normally or fails intentionally for DLQ testing
         */
        @ServiceOperation("processEvent")
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Implementation of {@link DlqService} that fails intentionally for events with ID
     * starting with {@code "fail-"} so those records are routed to the dead-letter topic.
     */
    static class DlqServiceImpl implements DlqService {

        @Override
        public Future<Void> processEvent(TestEvent event) {
            dlqHandlerInvocations.incrementAndGet();
            if (event.id().startsWith("fail-")) {
                return Future.failedFuture(new RuntimeException("Intentional failure for DLQ test: " + event.id()));
            }
            dlqHandlerReceived.add(event);
            return Future.succeededFuture();
        }
    }

    // --- Lifecycle ---

    /**
     * Starts Kafka, registers codecs, builds all registries and deploys all verticles.
     *
     * @param vertx the Vert.x instance injected by the JUnit 5 extension
     * @param ctx   the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        skipReceived.clear();
        dlqHandlerReceived.clear();
        dlqHandlerInvocations.set(0);

        // --- Register local event bus codecs (idempotent) ---
        tryRegisterCodec(vertx, "dispatch.envelope");
        tryRegisterCodec(vertx, "dispatch.result");

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));

        // --- Build registries for skip scenario (Model 1 via @KafkaSource) ---
        SkipServiceImpl skipImpl = new SkipServiceImpl();
        ServiceContractRegistry skipRegistry = ServiceContractRegistry.build(Set.of(skipImpl), configParser());
        KafkaConsumerRegistry skipConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(),
                Set.of(),
                skipRegistry,
                ServiceTargetResolver.of(skipRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Build registries for DLQ scenario (Model 2 via KafkaConsumerBinding) ---
        DlqServiceImpl dlqImpl = new DlqServiceImpl();
        ServiceContractRegistry dlqServiceRegistry = ServiceContractRegistry.build(Set.of(dlqImpl), configParser());

        KafkaConsumerBinding<TestEvent> dlqBinding = KafkaConsumerBinding.builder("dlq-event-binding", TestEvent.class)
                .topic("it.dlq.events")
                .groupId("it-dlq-group")
                .dispatchTo(DlqService.class, "processEvent")
                .errorStrategy(ErrorStrategy.DEAD_LETTER)
                .commitStrategy(CommitStrategy.MANUAL)
                .deadLetterTopic("it.dlq.events.dlq")
                .build();
        KafkaConsumerRegistry dlqConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(dlqBinding),
                Set.of(),
                dlqServiceRegistry,
                ServiceTargetResolver.of(dlqServiceRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Deploy service verticles ---
        ServiceExceptionMapper exceptionMapper = new ServiceExceptionMapper();
        ServiceResiliencePipelineFactory resiliencePipelineFactory =
                KafkaTestSupport.resiliencePipelineFactory(vertx, config);

        ServiceVerticle<SkipService> skipVerticle = new ServiceVerticle<>(
                skipRegistry.resolve(SkipService.class), exceptionMapper, List.of(), resiliencePipelineFactory, null);

        ServiceVerticle<DlqService> dlqVerticle = new ServiceVerticle<>(
                dlqServiceRegistry.resolve(DlqService.class),
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
        deployments.add(vertx.deployVerticle(skipVerticle));
        deployments.add(vertx.deployVerticle(dlqVerticle));

        ServiceTargetResolver skipResolver = ServiceTargetResolver.of(skipRegistry);
        ServiceTargetResolver dlqResolver = ServiceTargetResolver.of(dlqServiceRegistry);

        for (ConsumerEntry entry : skipConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        skipResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        for (ConsumerEntry entry : dlqConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        List.of(),
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        dlqResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        // After all verticles are deployed, allow a brief settle period for consumer group
        // rebalancing and partition assignment before tests start producing messages.
        Future.join(deployments).onComplete(ctx.succeeding(cf -> vertx.setTimer(3_000L, id -> ctx.completeNow())));
    }

    // --- Tests ---

    @Test
    @DisplayName("should skip bad messages and continue processing valid ones (SKIP strategy)")
    void shouldSkipBadMessage(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce invalid JSON (not a valid TestEvent)
        byte[] badPayload = "not-valid-json!!!".getBytes();
        producerFactory
                .send("it.skip.events", "bad-key", badPayload, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a valid message after the bad one
        TestEvent valid = new TestEvent("skip-valid-001", "valid-after-bad");
        byte[] validBytes = DatabindCodec.mapper().writeValueAsBytes(valid);
        producerFactory
                .send("it.skip.events", "valid-key", validBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // The valid message should arrive; the bad one should have been silently skipped
        waitForMessages(skipReceived, 1, 10_000);

        assertTrue(
                skipReceived.stream().anyMatch(e -> "skip-valid-001".equals(e.id())),
                "Valid event after bad message should still be processed");
    }

    @Test
    @DisplayName("should publish failed records to the dead-letter topic (DEAD_LETTER strategy)")
    void shouldPublishToDlq(Vertx vertx) throws Exception {
        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce a message that causes the service handler to fail (triggering DLQ routing)
        TestEvent failEvent = new TestEvent("fail-dlq-001", "this-will-fail");
        byte[] failBytes = DatabindCodec.mapper().writeValueAsBytes(failEvent);
        producerFactory
                .send("it.dlq.events", "fail-key", failBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a normal message to verify the consumer continues after the DLQ record
        TestEvent normalEvent = new TestEvent("dlq-normal-001", "normal-after-dlq");
        byte[] normalBytes = DatabindCodec.mapper().writeValueAsBytes(normalEvent);
        producerFactory
                .send("it.dlq.events", "normal-key", normalBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // The DLQ record should appear in the dead-letter topic
        KafkaConsumerRecord<String, byte[]> dlqRecord =
                consumeOneRecord(vertx, "it.dlq.events.dlq", "dlq-verify-group");

        assertNotNull(dlqRecord, "Dead-letter topic should contain the failed record");

        // Verify the DLQ record carries the original payload bytes
        TestEvent dlqPayload = DatabindCodec.mapper().readValue(dlqRecord.value(), TestEvent.class);
        assertEquals("fail-dlq-001", dlqPayload.id(), "DLQ record should contain the original event payload");

        // The normal message should still be processed (consumer continued after DLQ routing)
        waitForMessages(dlqHandlerReceived, 1, 10_000);
        assertTrue(
                dlqHandlerReceived.stream().anyMatch(e -> "dlq-normal-001".equals(e.id())),
                "Normal message after DLQ record should still be processed");
    }

    @Test
    @DisplayName("should continue consuming after multiple skip errors")
    void shouldContinueAfterMultipleSkipErrors(Vertx vertx) throws Exception {
        // Clear state from previous tests
        skipReceived.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce several bad messages
        for (int i = 0; i < 3; i++) {
            byte[] badPayload = ("{{bad-json-" + i + "}}").getBytes();
            producerFactory
                    .send("it.skip.events", "bad-key-" + i, badPayload, null)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        }

        // Produce a valid message after the bad ones
        TestEvent valid = new TestEvent("resilience-valid-001", "after-errors");
        byte[] validBytes = DatabindCodec.mapper().writeValueAsBytes(valid);
        producerFactory
                .send("it.skip.events", "resilience-valid-key", validBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(skipReceived, 1, 10_000);

        assertTrue(
                skipReceived.stream().anyMatch(e -> "resilience-valid-001".equals(e.id())),
                "Consumer should still be running after multiple deserialization errors");
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

    /**
     * Creates a disposable Kafka consumer, subscribes to {@code topic}, polls a single record
     * and returns it. The consumer is closed after the record is received.
     * Returns {@code null} if no record arrives within 10 seconds.
     *
     * @param vertx         the Vert.x instance
     * @param topic         the topic to subscribe to
     * @param consumerGroup a unique consumer group ID for this test
     * @return the first received record, or {@code null} if the timeout expires
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private KafkaConsumerRecord<String, byte[]> consumeOneRecord(Vertx vertx, String topic, String consumerGroup)
            throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<KafkaConsumerRecord<String, byte[]>> ref =
                new java.util.concurrent.atomic.AtomicReference<>();

        Map<String, String> props = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", consumerGroup,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer",
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "true");

        KafkaConsumer<String, byte[]> consumer = KafkaConsumer.create(vertx, props, String.class, byte[].class);
        consumer.handler(record -> {
            ref.compareAndSet(null, record);
            consumer.close();
        });
        consumer.subscribe(topic);

        long deadline = System.currentTimeMillis() + 10_000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        return ref.get();
    }

    /**
     * Asserts that {@code object} is not null with the given failure message.
     *
     * @param object  the object to check
     * @param message the failure message
     */
    private static void assertNotNull(Object object, String message) {
        org.junit.jupiter.api.Assertions.assertNotNull(object, message);
    }
}
