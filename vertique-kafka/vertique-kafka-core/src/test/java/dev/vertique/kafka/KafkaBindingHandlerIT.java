// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.ServiceVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for a {@link KafkaConsumerBinding} that carries a {@link KafkaRecordHandler}
 * instead of a {@code dispatchTo} target, against a real Kafka broker.
 *
 * <p>Verified:
 * <ul>
 *   <li>The handler receives the decoded {@link KafkaMessage} with key, topic, partition, offset
 *       and headers, and a {@code MANUAL} commit follows its success.</li>
 *   <li>The binding keeps its filter and its interceptors.</li>
 *   <li>A failing handler is retried and then dead-lettered under the binding's retry strategy.</li>
 *   <li>A {@code dispatchTo} binding beside a handler binding still reaches its service.</li>
 * </ul>
 *
 * <p>Each test owns its topics and groups, so no test sees another's records. A {@code HandlerRig}
 * deploys the consumers of a test and releases every resource in {@code finally}.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 60, unit = TimeUnit.SECONDS)
public class KafkaBindingHandlerIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    private static final long AWAIT_MS = 30_000L;

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    /** A terminal disposition the interceptor saw for the record at {@code offset}. */
    record Completion(long offset, KafkaTerminalOutcome outcome) {}

    /** Records every terminal disposition of the consumer it is attached to. */
    static class RecordingInterceptor implements KafkaConsumerInterceptor {

        final List<Completion> completions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {
            completions.add(new Completion(event.identity().offset(), event.outcome()));
        }

        /**
         * Returns the dispositions reported for one record, in the order they were reported.
         *
         * @param offset the record's offset
         * @return the outcomes for that record
         */
        List<KafkaTerminalOutcome> outcomesAt(long offset) {
            synchronized (completions) {
                return completions.stream()
                        .filter(completion -> completion.offset() == offset)
                        .map(Completion::outcome)
                        .toList();
            }
        }
    }

    /** Service contract of the {@code dispatchTo} binding. Request-reply, so a failure reaches the consumer. */
    @ServiceContract(value = "binding-handler-it-service")
    interface DispatchedService {

        @ServiceOperation("processEvent")
        Future<Void> processEvent(TestEvent event);
    }

    /** Records the ids it received; fails for ids starting with {@code "fail-"}. */
    static class DispatchedServiceImpl implements DispatchedService {

        final List<String> received = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Future<Void> processEvent(TestEvent event) {
            if (event.id().startsWith("fail-")) {
                return Future.failedFuture(new RuntimeException("intentional failure for " + event.id()));
            }
            received.add(event.id());
            return Future.succeededFuture();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a binding handler receives the decoded message and the record commits after it succeeds")
    void bindingHandlerReceivesTheDecodedMessageAndCommitsAfterItSucceeds(Vertx vertx) throws Exception {
        // Given: a MANUAL binding whose handler holds its future open on a promise
        String topic = "it.binding.handler.events";
        String group = "it-binding-handler-group";
        CompletableFuture<KafkaMessage<TestEvent>> called = new CompletableFuture<>();
        Promise<Void> open = Promise.promise();
        KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("handler-binding", TestEvent.class)
                .topic(topic)
                .groupId(group)
                .commitStrategy(CommitStrategy.MANUAL)
                .handler(message -> {
                    called.complete(message);
                    return open.future();
                })
                .build();
        HandlerRig rig = HandlerRig.bind(vertx, binding);
        try {
            // When: one JSON record with a key and a header arrives, and the handler is still open
            RecordMetadata sent = rig.produce(topic, "k-1", new TestEvent("evt-1", "first"), Map.of("x-trace", "t-1"));
            KafkaMessage<TestEvent> message = called.get(AWAIT_MS, TimeUnit.MILLISECONDS);
            OptionalLong committedWhileOpen = rig.committedOffsetStaysUnsetFor(group, topic, 1_000L);

            // Then: the handler got the decoded message with the record's coordinates and headers
            assertEquals(new TestEvent("evt-1", "first"), message.value());
            assertEquals("k-1", message.key());
            assertEquals(topic, message.topic());
            assertEquals(sent.getPartition(), message.partition());
            assertEquals(sent.getOffset(), message.offset());
            assertEquals("t-1", message.headers().asMap().get("x-trace"));

            // And: nothing was committed while the handler was open
            assertEquals(OptionalLong.empty(), committedWhileOpen);

            // When: the handler succeeds
            open.complete();

            // Then: the group commits the record's offset plus one
            assertEquals(
                    OptionalLong.of(sent.getOffset() + 1),
                    rig.awaitCommittedOffset(group, topic, sent.getOffset() + 1));
        } finally {
            open.tryComplete();
            rig.close();
        }
    }

    @Test
    @DisplayName("a binding handler keeps the filter and the interceptors")
    void bindingHandlerKeepsTheFilterAndTheInterceptors(Vertx vertx) throws Exception {
        // Given: a handler binding with a header filter and a recording interceptor
        String topic = "it.binding.handler.filter.events";
        String group = "it-binding-handler-filter-group";
        List<TestEvent> handled = Collections.synchronizedList(new ArrayList<>());
        RecordingInterceptor interceptor = new RecordingInterceptor();
        KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder(
                        "handler-filter-binding", TestEvent.class)
                .topic(topic)
                .groupId(group)
                .commitStrategy(CommitStrategy.MANUAL)
                .filter(KafkaRecordFilter.headerEquals("x-event-type", "accepted"))
                .handler(message -> {
                    handled.add(message.value());
                    return Future.succeededFuture();
                })
                .build();
        HandlerRig rig = HandlerRig.bind(vertx, binding, new JsonObject(), interceptor);
        try {
            // When: a record without the header and then a record with it arrive
            RecordMetadata rejected = rig.produce(topic, "k-1", new TestEvent("rejected-1", "no header"), Map.of());
            RecordMetadata accepted = rig.produce(
                    topic, "k-2", new TestEvent("accepted-1", "header"), Map.of("x-event-type", "accepted"));
            OptionalLong committed = rig.awaitCommittedOffset(group, topic, accepted.getOffset() + 1);
            awaitTrue(() -> interceptor.completions.size() >= 2);

            // Then: the handler ran once, for the accepted record
            assertEquals(List.of(new TestEvent("accepted-1", "header")), List.copyOf(handled));

            // And: each record reached one disposition, SKIP for the rejected and SUCCESS for the accepted
            assertEquals(List.of(KafkaTerminalOutcome.SKIP), interceptor.outcomesAt(rejected.getOffset()));
            assertEquals(List.of(KafkaTerminalOutcome.SUCCESS), interceptor.outcomesAt(accepted.getOffset()));
            assertEquals(2, interceptor.completions.size(), "no record should report a second disposition");

            // And: the committed offset passes both records
            assertEquals(OptionalLong.of(accepted.getOffset() + 1), committed);
        } finally {
            rig.close();
        }
    }

    @Test
    @DisplayName("a failing binding handler is retried then dead-lettered")
    void failingBindingHandlerIsRetriedThenDeadLettered(Vertx vertx) throws Exception {
        // Given: a MANUAL RETRY handler binding, two retries, a short backoff, dead-letter on exhaustion
        String topic = "it.binding.handler.retry.events";
        String group = "it-binding-handler-retry-group";
        String deadLetterTopic = topic + ".dlq";
        Map<String, Integer> callsById = Collections.synchronizedMap(new HashMap<>());
        List<String> accepted = Collections.synchronizedList(new ArrayList<>());
        RecordingInterceptor interceptor = new RecordingInterceptor();
        KafkaConsumerBinding<TestEvent> binding = KafkaConsumerBinding.builder("handler-retry", TestEvent.class)
                .topic(topic)
                .groupId(group)
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.RETRY)
                .deadLetterTopic(deadLetterTopic)
                .handler(message -> {
                    callsById.merge(message.value().id(), 1, Integer::sum);
                    if (message.value().id().startsWith("poison-")) {
                        return Future.failedFuture(new RuntimeException("intentional handler failure"));
                    }
                    accepted.add(message.value().id());
                    return Future.succeededFuture();
                })
                .build();
        JsonObject retry = new JsonObject()
                .put(
                        "retry",
                        new JsonObject()
                                .put("maxRetries", 2)
                                .put("backoffMs", 50L)
                                .put("exhaustedStrategy", "DEAD_LETTER"));
        HandlerRig rig = HandlerRig.bind(vertx, binding, retry, interceptor);
        try {
            // When: a record the handler always rejects arrives, then a record it accepts
            RecordMetadata poison = rig.produce(topic, "k-1", new TestEvent("poison-1", "always fails"), Map.of());
            RecordMetadata good = rig.produce(topic, "k-2", new TestEvent("good-1", "fine"), Map.of());
            List<TestEvent> deadLettered = rig.readEvents(deadLetterTopic, 1);
            OptionalLong committed = rig.awaitCommittedOffset(group, topic, good.getOffset() + 1);
            awaitTrue(() -> interceptor.outcomesAt(poison.getOffset()).size() >= 3);

            // Then: the handler ran three times for the first record: the attempt and two retries
            assertEquals(3, callsById.get("poison-1"));

            // And: the interceptor saw two scheduled retries and then the dead-letter publish
            assertEquals(
                    List.of(
                            KafkaTerminalOutcome.RETRY_SCHEDULED,
                            KafkaTerminalOutcome.RETRY_SCHEDULED,
                            KafkaTerminalOutcome.DLQ_PUBLISHED),
                    interceptor.outcomesAt(poison.getOffset()));

            // And: the dead-letter topic holds the first record
            assertEquals(List.of(new TestEvent("poison-1", "always fails")), deadLettered);

            // And: the offset is committed past it and the second record was handled
            assertEquals(OptionalLong.of(good.getOffset() + 1), committed);
            assertEquals(List.of("good-1"), List.copyOf(accepted));
        } finally {
            rig.close();
        }
    }

    @Test
    @DisplayName("a dispatchTo binding still reaches its service beside a handler binding")
    void dispatchToBindingStillReachesItsServiceBesideAHandlerBinding(Vertx vertx) throws Exception {
        // Given: one registry with a filtered, dead-lettering dispatchTo binding and a handler binding
        String serviceTopic = "it.binding.dispatch.events";
        String serviceGroup = "it-binding-dispatch-group";
        String serviceDeadLetterTopic = serviceTopic + ".dlq";
        String handlerTopic = "it.binding.beside.handler.events";
        String handlerGroup = "it-binding-beside-handler-group";
        DispatchedServiceImpl service = new DispatchedServiceImpl();
        List<KafkaMessage<TestEvent>> handled = Collections.synchronizedList(new ArrayList<>());
        KafkaConsumerBinding<TestEvent> dispatching = KafkaConsumerBinding.builder("dispatch-binding", TestEvent.class)
                .topic(serviceTopic)
                .groupId(serviceGroup)
                .dispatchTo(DispatchedService.class, "processEvent")
                .filter(KafkaRecordFilter.headerEquals("x-event-type", "accepted"))
                .commitStrategy(CommitStrategy.MANUAL)
                .errorStrategy(ErrorStrategy.DEAD_LETTER)
                .deadLetterTopic(serviceDeadLetterTopic)
                .build();
        KafkaConsumerBinding<TestEvent> handling = KafkaConsumerBinding.builder(
                        "beside-handler-binding", TestEvent.class)
                .topic(handlerTopic)
                .groupId(handlerGroup)
                .commitStrategy(CommitStrategy.MANUAL)
                .handler(message -> {
                    handled.add(message);
                    return Future.succeededFuture();
                })
                .build();
        HandlerRig rig = HandlerRig.bindBoth(vertx, dispatching, handling, service);
        try {
            // When: the first topic gets an unaccepted record, an accepted one and an accepted one the
            // service fails on, and the second topic gets one record
            Map<String, String> accepted = Map.of("x-event-type", "accepted");
            rig.produce(serviceTopic, "k-1", new TestEvent("skipped-1", "no header"), Map.of());
            rig.produce(serviceTopic, "k-2", new TestEvent("ok-1", "reaches the service"), accepted);
            RecordMetadata failing =
                    rig.produce(serviceTopic, "k-3", new TestEvent("fail-1", "service fails"), accepted);
            rig.produce(handlerTopic, "k-4", new TestEvent("handler-1", "reaches the handler"), Map.of());
            List<TestEvent> deadLettered = rig.readEvents(serviceDeadLetterTopic, 1);
            OptionalLong serviceCommitted =
                    rig.awaitCommittedOffset(serviceGroup, serviceTopic, failing.getOffset() + 1);
            awaitTrue(() -> !handled.isEmpty());
            awaitTrue(() -> !service.received.isEmpty());

            // Then: the service received exactly the accepted record
            assertEquals(List.of("ok-1"), List.copyOf(service.received));

            // And: the failed record is in the first binding's dead-letter topic and the offset moved past it
            assertEquals(List.of(new TestEvent("fail-1", "service fails")), deadLettered);
            assertEquals(OptionalLong.of(failing.getOffset() + 1), serviceCommitted);

            // And: the handler received only the second topic's record
            assertEquals(1, handled.size(), "the handler should have received exactly one record");
            KafkaMessage<TestEvent> handledMessage = handled.get(0);
            assertEquals(new TestEvent("handler-1", "reaches the handler"), handledMessage.value());
            assertEquals(handlerTopic, handledMessage.topic());

            // And: the service received nothing from the second topic
            assertFalse(service.received.contains("handler-1"), "the service should not see the handler's topic");
        } finally {
            rig.close();
        }
    }

    // --- Helpers ---

    /**
     * Polls until the condition holds, failing the test if it does not within {@link #AWAIT_MS}.
     *
     * @param condition the condition to wait for
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached within " + AWAIT_MS + " ms");
    }

    /**
     * Deploys the consumers of one test and offers the broker-side reads the test asserts on:
     * producing a record, the group's committed offset, and the records of a dead-letter topic.
     * {@link #close()} undeploys everything and closes the producer and the admin client.
     */
    static final class HandlerRig {

        private final Vertx vertx;
        private final KafkaProducerFactory producer;
        private final Admin admin;
        private final List<String> deploymentIds = new ArrayList<>();

        private HandlerRig(Vertx vertx) {
            this.vertx = vertx;
            KafkaConfig config = KafkaConfig.fromConfig(baseConfig(), configParser());
            this.producer = new KafkaProducerFactory(
                    vertx, config, KafkaTestSupport.noOpPropagator(), KafkaTestSupport.jsonSerdeRegistry());
            this.admin = Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()));
        }

        /**
         * Builds the registry for one binding that holds a handler, and deploys its consumer.
         *
         * @param vertx the Vert.x instance
         * @param binding the handler binding
         * @param interceptors the interceptors attached to the consumer
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static HandlerRig bind(
                Vertx vertx, KafkaConsumerBinding<TestEvent> binding, KafkaConsumerInterceptor... interceptors)
                throws Exception {
            return bind(vertx, binding, new JsonObject(), interceptors);
        }

        /**
         * Builds the registry for one binding that holds a handler, and deploys its consumer.
         *
         * @param vertx the Vert.x instance
         * @param binding the handler binding
         * @param consumerConfig overrides under {@code kafka.consumers.{binding name}}
         * @param interceptors the interceptors attached to the consumer
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static HandlerRig bind(
                Vertx vertx,
                KafkaConsumerBinding<TestEvent> binding,
                JsonObject consumerConfig,
                KafkaConsumerInterceptor... interceptors)
                throws Exception {
            HandlerRig rig = new HandlerRig(vertx);
            try {
                rig.deploy(
                        Set.of(binding),
                        Map.of(binding.name(), consumerConfig),
                        ServiceContractRegistry.build(Set.of(), configParser()),
                        null,
                        List.of(interceptors));
            } catch (Exception | Error e) {
                rig.close();
                throw e;
            }
            return rig;
        }

        /**
         * Builds one registry holding a {@code dispatchTo} binding and a handler binding, deploys
         * the service behind the first and a consumer for each.
         *
         * @param vertx the Vert.x instance
         * @param dispatching the {@code dispatchTo} binding
         * @param handling the handler binding
         * @param service the implementation behind the {@code dispatchTo} binding
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static HandlerRig bindBoth(
                Vertx vertx,
                KafkaConsumerBinding<TestEvent> dispatching,
                KafkaConsumerBinding<TestEvent> handling,
                DispatchedServiceImpl service)
                throws Exception {
            HandlerRig rig = new HandlerRig(vertx);
            try {
                rig.deploy(
                        Set.of(dispatching, handling),
                        Map.of(),
                        ServiceContractRegistry.build(Set.of(service), configParser()),
                        service,
                        List.of());
            } catch (Exception | Error e) {
                rig.close();
                throw e;
            }
            return rig;
        }

        /**
         * Produces one JSON record.
         *
         * @param topic the topic
         * @param key the record key
         * @param event the value, encoded as JSON
         * @param headers text headers; may be empty
         * @return the broker's metadata for the record
         * @throws Exception if the send fails
         */
        RecordMetadata produce(String topic, String key, TestEvent event, Map<String, String> headers)
                throws Exception {
            byte[] bytes = DatabindCodec.mapper().writeValueAsBytes(event);
            return producer.send(topic, key, bytes, KafkaRecordHeaders.of(headers))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(AWAIT_MS, TimeUnit.MILLISECONDS);
        }

        /**
         * Reads the group's committed offset on a topic.
         *
         * @param group the consumer group
         * @param topic the topic
         * @return the committed offset, or empty when the group has none on that topic
         * @throws Exception if the broker cannot be asked
         */
        OptionalLong committedOffset(String group, String topic) throws Exception {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            return offsets.entrySet().stream()
                    .filter(entry -> entry.getKey().topic().equals(topic) && entry.getValue() != null)
                    .mapToLong(entry -> entry.getValue().offset())
                    .max();
        }

        /**
         * Waits until the group's committed offset on a topic equals {@code expected}, or the wait
         * runs out.
         *
         * @param group the consumer group
         * @param topic the topic
         * @param expected the offset to wait for
         * @return the last offset read
         * @throws Exception if the broker cannot be asked
         */
        OptionalLong awaitCommittedOffset(String group, String topic, long expected) throws Exception {
            long deadline = System.currentTimeMillis() + AWAIT_MS;
            OptionalLong last = committedOffset(group, topic);
            while (!last.equals(OptionalLong.of(expected)) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                last = committedOffset(group, topic);
            }
            return last;
        }

        /**
         * Reads the committed offset repeatedly over a window and returns it, stopping early with
         * the offset as soon as one is committed.
         *
         * @param group the consumer group
         * @param topic the topic
         * @param windowMs how long to watch
         * @return empty when nothing was committed during the whole window
         * @throws Exception if the broker cannot be asked
         */
        OptionalLong committedOffsetStaysUnsetFor(String group, String topic, long windowMs) throws Exception {
            long deadline = System.currentTimeMillis() + windowMs;
            OptionalLong seen = committedOffset(group, topic);
            while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                seen = committedOffset(group, topic);
            }
            return seen;
        }

        /**
         * Reads a topic from the beginning with a throwaway group until it holds {@code expected}
         * records or the wait runs out.
         *
         * @param topic the topic
         * @param expected how many records to wait for
         * @return the decoded values read, in order
         * @throws Exception if a record cannot be decoded
         */
        List<TestEvent> readEvents(String topic, int expected) throws Exception {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-binding-handler-reader-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            List<TestEvent> events = new ArrayList<>();
            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(props)) {
                reader.subscribe(List.of(topic));
                long deadline = System.currentTimeMillis() + AWAIT_MS;
                while (events.size() < expected && System.currentTimeMillis() < deadline) {
                    for (ConsumerRecord<String, byte[]> record : reader.poll(Duration.ofMillis(200))) {
                        events.add(DatabindCodec.mapper().readValue(record.value(), TestEvent.class));
                    }
                }
            }
            return events;
        }

        /** Undeploys every consumer and service, then closes the producer and the admin client. */
        void close() {
            for (String deploymentId : deploymentIds) {
                try {
                    vertx.undeploy(deploymentId)
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Best effort: the shared broker outlives the test, the Vert.x instance is closed after it
                }
            }
            deploymentIds.clear();
            try {
                producer.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best effort, as above
            }
            admin.close(Duration.ofSeconds(5));
        }

        private void deploy(
                Set<KafkaConsumerBinding<?>> bindings,
                Map<String, JsonObject> consumerOverrides,
                ServiceContractRegistry serviceRegistry,
                DispatchedServiceImpl service,
                List<KafkaConsumerInterceptor> interceptors)
                throws Exception {
            registerCodec("dispatch.envelope");
            registerCodec("dispatch.result");

            // Start each consumer at the beginning of its topic, so no record races the assignment
            JsonObject consumers = new JsonObject();
            for (KafkaConsumerBinding<?> binding : bindings) {
                JsonObject consumer = new JsonObject()
                        .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                        .mergeIn(consumerOverrides.getOrDefault(binding.name(), new JsonObject()));
                consumers.put(binding.name(), consumer);
            }
            JsonObject config = baseConfig();
            config.getJsonObject("kafka").put("consumers", consumers);

            ServiceTargetResolver resolver = ServiceTargetResolver.of(serviceRegistry);
            KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(
                    bindings,
                    Set.of(),
                    serviceRegistry,
                    resolver,
                    KafkaTestSupport.jsonSerdeRegistry(),
                    KafkaConfig.fromConfig(config, configParser()));

            if (service != null) {
                deployAndWait(new ServiceVerticle<>(
                        serviceRegistry.resolve(DispatchedService.class),
                        new ServiceExceptionMapper(),
                        List.of(),
                        KafkaTestSupport.resiliencePipelineFactory(vertx, config),
                        null));
            }
            for (ConsumerEntry entry : registry.entries()) {
                if (entry.config().enabled()) {
                    deployAndWait(new KafkaConsumerVerticle(
                            entry,
                            interceptors,
                            producer,
                            KafkaTestSupport.requestSender(vertx),
                            resolver,
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder(),
                            KafkaTestSupport.jsonSerdeRegistry()));
                }
            }
        }

        private void deployAndWait(io.vertx.core.Verticle verticle) throws Exception {
            String id = vertx.deployVerticle(verticle)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(AWAIT_MS, TimeUnit.MILLISECONDS);
            deploymentIds.add(id);
        }

        private void registerCodec(String name) {
            try {
                vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
            } catch (IllegalStateException ignored) {
                // Already registered: idempotent
            }
        }

        private static JsonObject baseConfig() {
            return new JsonObject()
                    .put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        }
    }
}
