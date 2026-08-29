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
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for the {@link KafkaConsumerInterceptor} pipeline in
 * {@link KafkaConsumerVerticle}.
 *
 * <p>Verifies all six callbacks of the interceptor SPI against a real Kafka broker:
 * <ul>
 *   <li>{@code onRecord} — fired before dispatch with the correct context.</li>
 *   <li>{@code onSuccess} — fired after successful dispatch.</li>
 *   <li>{@code onError} — fired when dispatch fails.</li>
 *   <li>{@code beforeDispatch} with {@code filtered=true} — record is skipped entirely.</li>
 *   <li>{@code afterDispatch} (async) — called and awaited after successful dispatch.</li>
 *   <li>{@code recoverError} returning succeeded — record is committed instead of error strategy.</li>
 *   <li>{@code recoverError} returning failed — normal error strategy (SKIP) applies.</li>
 * </ul>
 *
 * <p>Each test uses a unique topic name so messages cannot cross test boundaries. The
 * Kafka container and all verticles are deployed once in {@link #setUp} and torn down
 * in {@link #tearDown}.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 90, unit = TimeUnit.SECONDS)
public class KafkaInterceptorIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    // --- Shared collectors ---

    /** Events processed by the base interceptor-test service handler. */
    static final List<TestEvent> received = Collections.synchronizedList(new ArrayList<>());

    /** Events processed by the failing service handler (for error interceptor tests). */
    static final List<TestEvent> failReceived = Collections.synchronizedList(new ArrayList<>());

    // --- Test interceptors (static inner classes) ---

    /**
     * Recording interceptor that tracks every callback invocation. Thread-safe via
     * {@link Collections#synchronizedList}.
     */
    static class RecordingInterceptor implements KafkaConsumerInterceptor {

        /** Keys ({@code topic:offset}) recorded by {@link #onRecord}. */
        final List<String> onRecordCalls = Collections.synchronizedList(new ArrayList<>());

        /** Keys recorded by {@link #onSuccess}. */
        final List<String> onSuccessCalls = Collections.synchronizedList(new ArrayList<>());

        /** Keys recorded by {@link #onError}. */
        final List<String> onErrorCalls = Collections.synchronizedList(new ArrayList<>());

        /** Keys recorded by {@link #afterDispatch}. */
        final List<String> afterDispatchCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onRecord(KafkaDispatchContext<?> ctx) {
            onRecordCalls.add(ctx.topic() + ":" + ctx.offset());
        }

        @Override
        public void onSuccess(KafkaDispatchContext<?> ctx) {
            onSuccessCalls.add(ctx.topic() + ":" + ctx.offset());
        }

        @Override
        public void onError(KafkaDispatchContext<?> ctx, Throwable error) {
            onErrorCalls.add(ctx.topic() + ":" + ctx.offset());
        }

        @Override
        public Future<Void> afterDispatch(KafkaDispatchContext<?> ctx) {
            afterDispatchCalls.add(ctx.topic() + ":" + ctx.offset());
            return Future.succeededFuture();
        }
    }

    /**
     * Filtering interceptor that marks records as filtered when the {@code x-action}
     * header equals {@code "skip-me"}.
     */
    static class FilteringInterceptor implements KafkaConsumerInterceptor {

        @Override
        public Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
            if ("skip-me".equals(ctx.headers().get("x-action"))) {
                return Future.succeededFuture(ctx.withFiltered(true));
            }
            return Future.succeededFuture(ctx);
        }
    }

    /**
     * Recovering interceptor that handles errors whose message contains {@code "recoverable"},
     * leaving all other errors to the normal error strategy.
     */
    static class RecoveringInterceptor implements KafkaConsumerInterceptor {

        /** Number of times {@link #recoverError} has accepted an error. */
        final AtomicInteger recoverCount = new AtomicInteger();

        @Override
        public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
            if (error.getMessage() != null && error.getMessage().contains("recoverable")) {
                recoverCount.incrementAndGet();
                return Future.succeededFuture(); // handled — commit and move on
            }
            return Future.failedFuture(error); // not handled — let normal error strategy proceed
        }
    }

    // --- Shared interceptor instances (set up once, inspected per test) ---

    static RecordingInterceptor recordingInterceptor;
    static FilteringInterceptor filteringInterceptor;
    static RecoveringInterceptor recoveringInterceptor;

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    // --- Service contracts ---

    /**
     * Base service contract for the main interceptor-pipeline tests.
     */
    @ServiceContract(value = "interceptor-test-service")
    interface InterceptorTestService {

        /**
         * Processes an event normally. Used for {@code onRecord}, {@code onSuccess},
         * {@code afterDispatch}, and filter tests.
         *
         * @param event the event to process
         * @return a future that completes when processing is done
         */
        @ServiceOperation("processEvent")
        @OneWay
        Future<Void> processEvent(TestEvent event);
    }

    /**
     * Service contract for error-scenario interceptor tests. Uses request-reply semantics
     * (no {@link OneWay}) so failures propagate back to the Kafka consumer.
     */
    @ServiceContract(value = "interceptor-fail-service")
    interface FailingService {

        /**
         * Fails intentionally for events named {@code "fail-recoverable"} or
         * {@code "fail-unrecoverable"}, so the interceptor recovery and error callbacks
         * can be exercised.
         *
         * @param event the event to process
         * @return a failed future for events that trigger intentional failures
         */
        @ServiceOperation("processEvent")
        Future<Void> processEvent(TestEvent event);
    }

    // --- Service implementations ---

    /**
     * Normal implementation that appends each event to {@link #received}.
     */
    static class InterceptorTestServiceImpl implements InterceptorTestService {

        @KafkaSource(topic = "it.interceptor.events", groupId = "it-interceptor-group")
        @Override
        public Future<Void> processEvent(TestEvent event) {
            received.add(event);
            return Future.succeededFuture();
        }
    }

    /**
     * Failing implementation: throws a labelled exception for "fail-*" events so the
     * interceptor's {@code onError} and {@code recoverError} callbacks are exercised.
     */
    static class FailingServiceImpl implements FailingService {

        @Override
        public Future<Void> processEvent(TestEvent event) {
            if ("fail-recoverable".equals(event.name())) {
                return Future.failedFuture(new RuntimeException("recoverable error"));
            }
            if ("fail-unrecoverable".equals(event.name())) {
                return Future.failedFuture(new RuntimeException("unrecoverable error"));
            }
            failReceived.add(event);
            return Future.succeededFuture();
        }
    }

    // --- Lifecycle ---

    /**
     * Starts Kafka, wires up service and consumer verticles for all test scenarios, and
     * waits for consumer group assignment before tests run.
     *
     * @param vertx the Vert.x instance injected by the JUnit 5 extension
     * @param ctx   the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        received.clear();
        failReceived.clear();

        // --- Event bus codecs (idempotent) ---
        tryRegisterCodec(vertx, "dispatch.envelope");
        tryRegisterCodec(vertx, "dispatch.result");

        // --- Shared interceptor instances ---
        recordingInterceptor = new RecordingInterceptor();
        filteringInterceptor = new FilteringInterceptor();
        recoveringInterceptor = new RecoveringInterceptor();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));

        // --- Main interceptor test service (Model 1 via @KafkaSource) ---
        InterceptorTestServiceImpl mainImpl = new InterceptorTestServiceImpl();
        ServiceContractRegistry mainRegistry = ServiceContractRegistry.build(Set.of(mainImpl), configParser());
        KafkaConsumerRegistry mainConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(),
                Set.of(),
                mainRegistry,
                ServiceTargetResolver.of(mainRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Failing service (Model 2 via KafkaConsumerBinding, MANUAL + SKIP) ---
        FailingServiceImpl failImpl = new FailingServiceImpl();
        ServiceContractRegistry failRegistry = ServiceContractRegistry.build(Set.of(failImpl), configParser());

        KafkaConsumerBinding<TestEvent> failBinding = KafkaConsumerBinding.builder(
                        "interceptor-fail-binding", TestEvent.class)
                .topic("it.interceptor.fail.events")
                .groupId("it-interceptor-fail-group")
                .dispatchTo(FailingService.class, "processEvent")
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.SKIP)
                .build();
        KafkaConsumerRegistry failConsumerRegistry = KafkaConsumerRegistry.build(
                Set.of(failBinding),
                Set.of(),
                failRegistry,
                ServiceTargetResolver.of(failRegistry),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(config, configParser()));

        // --- Deploy service verticles ---
        ServiceExceptionMapper exceptionMapper = new ServiceExceptionMapper();
        ServiceResiliencePipelineFactory resiliencePipelineFactory =
                KafkaTestSupport.resiliencePipelineFactory(vertx, config);

        ServiceVerticle<InterceptorTestService> mainVerticle = new ServiceVerticle<>(
                mainRegistry.resolve(InterceptorTestService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);

        ServiceVerticle<FailingService> failVerticle = new ServiceVerticle<>(
                failRegistry.resolve(FailingService.class),
                exceptionMapper,
                List.of(),
                resiliencePipelineFactory,
                null);

        // --- Deploy Kafka consumer verticles with interceptors ---
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // All three interceptors attached to the main consumer
        List<KafkaConsumerInterceptor> mainInterceptors =
                List.of(recordingInterceptor, filteringInterceptor, recoveringInterceptor);

        List<Future<?>> deployments = new ArrayList<>();
        deployments.add(vertx.deployVerticle(mainVerticle));
        deployments.add(vertx.deployVerticle(failVerticle));

        ServiceTargetResolver mainResolver = ServiceTargetResolver.of(mainRegistry);
        ServiceTargetResolver failResolver = ServiceTargetResolver.of(failRegistry);

        for (ConsumerEntry entry : mainConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        mainInterceptors,
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        mainResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        // All three interceptors also attached to the failing consumer
        for (ConsumerEntry entry : failConsumerRegistry.entries()) {
            if (entry.config().enabled()) {
                deployments.add(vertx.deployVerticle(new KafkaConsumerVerticle(
                        entry,
                        mainInterceptors,
                        Set.of(),
                        producerFactory,
                        KafkaTestSupport.requestSender(vertx),
                        failResolver,
                        KafkaTestSupport.eventBusClient(vertx),
                        KafkaTestSupport.noOpInboundExecutionContextScope(),
                        KafkaTestSupport.noOpEnvelopeBuilder(),
                        KafkaTestSupport.jsonSerdeRegistry())));
            }
        }

        Future.join(deployments).onComplete(ctx.succeeding(cf -> vertx.setTimer(3_000L, id -> ctx.completeNow())));
    }

    // --- Tests ---

    @Test
    @DisplayName("should call onRecord before dispatch with correct topic and offset")
    void shouldCallOnRecordBeforeDispatch(Vertx vertx) throws Exception {
        recordingInterceptor.onRecordCalls.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent event = new TestEvent("on-record-001", "on-record-test");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.interceptor.events", "or-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(received, 1, 10_000);
        waitFor(() -> !recordingInterceptor.onRecordCalls.isEmpty(), 5_000);

        assertFalse(recordingInterceptor.onRecordCalls.isEmpty(), "onRecord should have been called at least once");
        assertTrue(
                recordingInterceptor.onRecordCalls.stream().anyMatch(k -> k.startsWith("it.interceptor.events:")),
                "onRecord call should record topic:offset with correct topic prefix");
    }

    @Test
    @DisplayName("should call onSuccess after successful dispatch")
    void shouldCallOnSuccessAfterDispatch(Vertx vertx) throws Exception {
        recordingInterceptor.onSuccessCalls.clear();
        int sizeBefore = received.size();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent event = new TestEvent("on-success-001", "on-success-test");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.interceptor.events", "os-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(received, sizeBefore + 1, 10_000);
        waitFor(() -> !recordingInterceptor.onSuccessCalls.isEmpty(), 5_000);

        assertFalse(recordingInterceptor.onSuccessCalls.isEmpty(), "onSuccess should have been called at least once");
        assertTrue(
                recordingInterceptor.onSuccessCalls.stream().anyMatch(k -> k.startsWith("it.interceptor.events:")),
                "onSuccess call should record topic:offset with correct topic prefix");
    }

    @Test
    @DisplayName("should call onError when dispatch fails")
    void shouldCallOnErrorOnFailure(Vertx vertx) throws Exception {
        recordingInterceptor.onErrorCalls.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // This event triggers a failure in FailingServiceImpl
        TestEvent event = new TestEvent("on-error-001", "fail-unrecoverable");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.interceptor.fail.events", "oe-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> !recordingInterceptor.onErrorCalls.isEmpty(), 10_000);

        assertFalse(recordingInterceptor.onErrorCalls.isEmpty(), "onError should have been called at least once");
        assertTrue(
                recordingInterceptor.onErrorCalls.stream().anyMatch(k -> k.startsWith("it.interceptor.fail.events:")),
                "onError call should record the fail topic");
    }

    @Test
    @DisplayName("should skip record when beforeDispatch sets filtered=true")
    void shouldFilterViaBeforeDispatch(Vertx vertx) throws Exception {
        int sizeBefore = received.size();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // Produce a record with the "skip-me" header — FilteringInterceptor will filter it
        TestEvent skipped = new TestEvent("filter-skip-001", "should-be-skipped");
        byte[] skippedBytes = DatabindCodec.mapper().writeValueAsBytes(skipped);
        producerFactory
                .send("it.interceptor.events", "filter-key-skip", skippedBytes, Map.of("x-action", "skip-me"))
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a normal record to confirm the consumer is still active
        TestEvent normal = new TestEvent("filter-normal-001", "should-be-processed");
        byte[] normalBytes = DatabindCodec.mapper().writeValueAsBytes(normal);
        producerFactory
                .send("it.interceptor.events", "filter-key-normal", normalBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(received, sizeBefore + 1, 10_000);

        // The skipped event should NOT be in the received list
        assertFalse(
                received.stream().anyMatch(e -> "filter-skip-001".equals(e.id())),
                "Filtered record should not reach the service handler");
        // The normal event SHOULD be in the received list
        assertTrue(
                received.stream().anyMatch(e -> "filter-normal-001".equals(e.id())),
                "Normal (non-filtered) record should reach the service handler");
    }

    @Test
    @DisplayName("should call afterDispatch async callback after successful dispatch")
    void shouldCallAfterDispatchAsync(Vertx vertx) throws Exception {
        recordingInterceptor.afterDispatchCalls.clear();
        int sizeBefore = received.size();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        TestEvent event = new TestEvent("after-dispatch-001", "after-dispatch-test");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.interceptor.events", "ad-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitForMessages(received, sizeBefore + 1, 10_000);
        waitFor(() -> !recordingInterceptor.afterDispatchCalls.isEmpty(), 5_000);

        assertFalse(
                recordingInterceptor.afterDispatchCalls.isEmpty(),
                "afterDispatch should have been called at least once");
        assertTrue(
                recordingInterceptor.afterDispatchCalls.stream().anyMatch(k -> k.startsWith("it.interceptor.events:")),
                "afterDispatch call should record topic:offset with correct topic prefix");
    }

    @Test
    @DisplayName("should recover error via interceptor recoverError and commit record")
    void shouldRecoverErrorViaInterceptor(Vertx vertx) throws Exception {
        int recoverCountBefore = recoveringInterceptor.recoverCount.get();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // "fail-recoverable" causes FailingServiceImpl to fail with "recoverable error"
        // RecoveringInterceptor accepts this and returns succeeded — consumer should move on
        TestEvent event = new TestEvent("recover-001", "fail-recoverable");
        byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
        producerFactory
                .send("it.interceptor.fail.events", "rec-key-1", bytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a normal record after recovery to confirm consumer did not halt
        TestEvent normal = new TestEvent("recover-normal-001", "normal-after-recovery");
        byte[] normalBytes = DatabindCodec.mapper().writeValueAsBytes(normal);
        producerFactory
                .send("it.interceptor.fail.events", "rec-key-normal", normalBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> recoveringInterceptor.recoverCount.get() > recoverCountBefore, 10_000);
        waitFor(() -> failReceived.stream().anyMatch(e -> "recover-normal-001".equals(e.id())), 10_000);

        assertTrue(
                recoveringInterceptor.recoverCount.get() > recoverCountBefore,
                "recoverError should have been invoked and accepted at least once");
        assertTrue(
                failReceived.stream().anyMatch(e -> "recover-normal-001".equals(e.id())),
                "Consumer should continue processing after interceptor recovery");
    }

    @Test
    @DisplayName("should fall through to SKIP strategy when recoverError declines")
    void shouldFallThroughWhenRecoverErrorDeclines(Vertx vertx) throws Exception {
        recordingInterceptor.onErrorCalls.clear();

        JsonObject config =
                new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        KafkaProducerFactory producerFactory = new KafkaProducerFactory(
                vertx,
                KafkaConfig.fromConfig(config, configParser()),
                KafkaTestSupport.noOpPropagator(),
                KafkaTestSupport.jsonSerdeRegistry());

        // "fail-unrecoverable" triggers an error that RecoveringInterceptor declines to recover
        TestEvent failing = new TestEvent("no-recover-002", "fail-unrecoverable");
        byte[] failBytes = DatabindCodec.mapper().writeValueAsBytes(failing);
        producerFactory
                .send("it.interceptor.fail.events", "nr-key-2", failBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        // Produce a normal record to verify the consumer skipped and continued (SKIP strategy)
        TestEvent normal = new TestEvent("no-recover-normal-002", "normal-after-unrecoverable");
        byte[] normalBytes = DatabindCodec.mapper().writeValueAsBytes(normal);
        producerFactory
                .send("it.interceptor.fail.events", "nr-key-normal-2", normalBytes, null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();

        waitFor(() -> !recordingInterceptor.onErrorCalls.isEmpty(), 10_000);
        waitFor(() -> failReceived.stream().anyMatch(e -> "no-recover-normal-002".equals(e.id())), 10_000);

        assertFalse(recordingInterceptor.onErrorCalls.isEmpty(), "onError should be called when recoverError declines");
        assertTrue(
                failReceived.stream().anyMatch(e -> "no-recover-normal-002".equals(e.id())),
                "Consumer should continue after SKIP on unrecoverable error");
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
     * Polls the given list until it contains at least {@code expected} elements or
     * {@code timeoutMs} has elapsed.
     *
     * @param list      the list to poll
     * @param expected  the minimum element count to wait for
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
