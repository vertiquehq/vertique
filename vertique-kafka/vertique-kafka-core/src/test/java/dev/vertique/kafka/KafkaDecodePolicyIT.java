// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for the decode policy of a handler binding, against a real Kafka broker.
 *
 * <p>Verified:
 * <ul>
 *   <li>A binding built with {@code ignoreUndecodable} acks and commits a record whose value does
 *       not decode, runs its callback once, reports {@code SKIP} and sends nothing to the
 *       dead-letter topic; the next valid record reaches the handler.</li>
 *   <li>The policy ignores a decode failure and nothing else: on the same binding a decodable record
 *       whose handler fails is still retried and does not run the callback.</li>
 *   <li>A binding of the same shape without the policy sends the undecodable record down the error
 *       strategy, as before.</li>
 *   <li>A decode failure that the deserializer marks transient is not ignored: it takes the error
 *       strategy, while a permanent one on the same binding is ignored.</li>
 * </ul>
 *
 * <p>The class timeout is longer than the standard 20 seconds because the broker may be starting.
 * Each test owns its topics and groups; a {@code DecodeRig} builds the records, deploys both
 * bindings and releases every resource in {@code finally}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class KafkaDecodePolicyIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    private static final long AWAIT_MS = 30_000L;

    // --- Test fixtures ---

    /** Simple event record used as the message payload. */
    record TestEvent(String id, String name) {}

    /** A terminal disposition the interceptor saw for the record at {@code offset} of {@code topic}. */
    record Completion(String topic, long offset, KafkaTerminalOutcome outcome) {}

    /** Records every terminal disposition of the consumers it is attached to. */
    static class RecordingInterceptor implements KafkaConsumerInterceptor {

        final List<Completion> completions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {
            completions.add(
                    new Completion(event.identity().topic(), event.identity().offset(), event.outcome()));
        }

        /**
         * Returns the dispositions reported for one record, in the order they were reported.
         *
         * @param topic the record's topic
         * @param offset the record's offset
         * @return the outcomes for that record
         */
        List<KafkaTerminalOutcome> outcomesAt(String topic, long offset) {
            synchronized (completions) {
                return completions.stream()
                        .filter(completion -> completion.topic().equals(topic) && completion.offset() == offset)
                        .map(Completion::outcome)
                        .toList();
            }
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("an undecodable record is ignored on the callback and committed")
    void undecodableRecordIsIgnoredOnTheCallbackAndCommitted(Vertx vertx) throws Exception {
        // Given: two MANUAL RETRY handler bindings of the same shape on separate topics, one that ignores
        // undecodable records and counts them, one that does not; the ignoring handler records the ids it gets
        AtomicInteger ignored = new AtomicInteger();
        List<String> ignoringHandled = Collections.synchronizedList(new ArrayList<>());
        DecodeRig rig = DecodeRig.open(
                vertx,
                ignored::incrementAndGet,
                message -> {
                    ignoringHandled.add(message.value().id());
                    return message.value().id().startsWith("fail-")
                            ? Future.failedFuture(new RuntimeException("intentional handler failure"))
                            : Future.succeededFuture();
                },
                message -> Future.succeededFuture());
        try {
            // When: each topic gets a record whose value is not JSON, then a valid record
            RecordMetadata ignoringGarbage = rig.produce(rig.ignoringTopic, DecodeRig.garbage());
            RecordMetadata ignoringValid = rig.produce(rig.ignoringTopic, DecodeRig.valid("ok-1"));
            RecordMetadata plainGarbage = rig.produce(rig.plainTopic, DecodeRig.garbage());
            rig.produce(rig.plainTopic, DecodeRig.valid("ok-2"));
            OptionalLong committed =
                    rig.awaitCommittedOffset(rig.ignoringGroup, rig.ignoringTopic, ignoringValid.getOffset() + 1);
            awaitTrue(() -> !rig.interceptor
                    .outcomesAt(rig.plainTopic, plainGarbage.getOffset())
                    .isEmpty());

            // Then: on the ignoring binding the callback ran once and the garbage record was skipped
            assertEquals(1, ignored.get(), "the callback should run once, for the garbage record");
            assertEquals(
                    List.of(KafkaTerminalOutcome.SKIP),
                    rig.interceptor.outcomesAt(rig.ignoringTopic, ignoringGarbage.getOffset()));

            // And: the valid record reached the handler and the offset is committed past both records
            assertEquals(List.of("ok-1"), List.copyOf(ignoringHandled));
            assertEquals(OptionalLong.of(ignoringValid.getOffset() + 1), committed);

            // And: nothing was dead-lettered for the ignoring binding
            assertTrue(
                    rig.readDeadLetters(rig.ignoringDeadLetterTopic, 1, 3_000L).isEmpty(),
                    "the ignoring binding should have dead-lettered nothing");

            // And: the binding without the policy retries the garbage record, as today
            assertEquals(
                    KafkaTerminalOutcome.RETRY_SCHEDULED,
                    rig.interceptor
                            .outcomesAt(rig.plainTopic, plainGarbage.getOffset())
                            .get(0));

            // When: a decodable record that the ignoring binding's handler fails arrives
            RecordMetadata failing = rig.produce(rig.ignoringTopic, DecodeRig.valid("fail-1"));
            awaitTrue(() -> !rig.interceptor
                    .outcomesAt(rig.ignoringTopic, failing.getOffset())
                    .isEmpty());

            // Then: it is retried, not ignored: the callback did not run again and the offset did not move
            assertEquals(
                    KafkaTerminalOutcome.RETRY_SCHEDULED,
                    rig.interceptor
                            .outcomesAt(rig.ignoringTopic, failing.getOffset())
                            .get(0));
            assertEquals(1, ignored.get(), "a handler failure must not run the undecodable callback");
            assertEquals(
                    OptionalLong.of(ignoringValid.getOffset() + 1),
                    rig.awaitCommittedOffset(rig.ignoringGroup, rig.ignoringTopic, ignoringValid.getOffset() + 1));
        } finally {
            rig.close();
        }
    }

    @Test
    @DisplayName("a transient decode failure takes the error strategy and a permanent one is ignored")
    void transientDecodeFailureTakesTheErrorStrategyAndAPermanentOneIsIgnored(Vertx vertx) throws Exception {
        // Given: an ignoring binding whose deserializer fails permanently on one marker and transiently on another
        AtomicInteger ignored = new AtomicInteger();
        List<String> handled = Collections.synchronizedList(new ArrayList<>());
        KafkaDeserializer<TestEvent> deserializer = (data, topic, headers) -> {
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.contains("registry-down")) {
                throw new DeserializationException("registry unreachable", new java.io.IOException("down"), true);
            }
            if (text.contains("not-json")) {
                throw new DeserializationException("not decodable", null);
            }
            try {
                return DatabindCodec.mapper().readValue(data, TestEvent.class);
            } catch (java.io.IOException e) {
                throw new DeserializationException("not decodable", e);
            }
        };
        DecodeRig rig = DecodeRig.open(
                vertx,
                ignored::incrementAndGet,
                message -> {
                    handled.add(message.value().id());
                    return Future.succeededFuture();
                },
                message -> Future.succeededFuture(),
                deserializer);
        try {
            // When: a permanent failure, a valid record and then a transient failure are produced
            RecordMetadata permanent = rig.produce(rig.ignoringTopic, DecodeRig.garbage());
            RecordMetadata valid = rig.produce(rig.ignoringTopic, DecodeRig.valid("ok-1"));
            RecordMetadata transientFailure =
                    rig.produce(rig.ignoringTopic, "registry-down".getBytes(StandardCharsets.UTF_8));
            awaitTrue(() -> !rig.interceptor
                    .outcomesAt(rig.ignoringTopic, transientFailure.getOffset())
                    .isEmpty());

            // Then: the permanent failure was ignored and the valid record reached the handler
            assertEquals(
                    List.of(KafkaTerminalOutcome.SKIP),
                    rig.interceptor.outcomesAt(rig.ignoringTopic, permanent.getOffset()));
            assertEquals(List.of("ok-1"), List.copyOf(handled));

            // And: the transient failure took the error strategy, so it is retried
            assertEquals(
                    KafkaTerminalOutcome.RETRY_SCHEDULED,
                    rig.interceptor
                            .outcomesAt(rig.ignoringTopic, transientFailure.getOffset())
                            .get(0));

            // And: the callback ran for the permanent failure alone, and the transient record is not committed
            assertEquals(1, ignored.get(), "the callback should run once, for the permanent failure");
            // The commit follows the settled records asynchronously, so it is awaited
            assertEquals(
                    OptionalLong.of(valid.getOffset() + 1),
                    rig.awaitCommittedOffset(rig.ignoringGroup, rig.ignoringTopic, valid.getOffset() + 1));
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
     * Deploys the two bindings of one test and offers the broker-side reads the test asserts on:
     * producing a record, a group's committed offset and the records of a dead-letter topic. It owns
     * the recording interceptor both consumers report to. {@link #close()} undeploys the consumers
     * and closes the producer and the admin client.
     */
    static final class DecodeRig {

        final String ignoringTopic;
        final String ignoringGroup;
        final String ignoringDeadLetterTopic;
        final String plainTopic;
        final RecordingInterceptor interceptor = new RecordingInterceptor();

        private final Vertx vertx;
        private final KafkaProducerFactory producer;
        private final Admin admin;
        private final List<String> deploymentIds = new ArrayList<>();

        private DecodeRig(Vertx vertx) {
            this.vertx = vertx;
            String suffix = UUID.randomUUID().toString();
            this.ignoringTopic = "it.decode.ignoring." + suffix;
            this.ignoringGroup = "it-decode-ignoring-" + suffix;
            this.ignoringDeadLetterTopic = ignoringTopic + ".dlq";
            this.plainTopic = "it.decode.plain." + suffix;
            KafkaConfig config = KafkaConfig.fromConfig(baseConfig(), configParser());
            this.producer = new KafkaProducerFactory(
                    vertx, config, KafkaTestSupport.noOpPropagator(), KafkaTestSupport.jsonSerdeRegistry());
            this.admin = Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()));
        }

        /**
         * A record value that is not JSON.
         *
         * @return the bytes
         */
        static byte[] garbage() {
            return "this is {not json".getBytes(StandardCharsets.UTF_8);
        }

        /**
         * A record value that decodes to a {@link TestEvent}.
         *
         * @param id the event id
         * @return the JSON bytes
         * @throws Exception if the event cannot be encoded
         */
        static byte[] valid(String id) throws Exception {
            return DatabindCodec.mapper().writeValueAsBytes(new TestEvent(id, "valid"));
        }

        /**
         * Builds the two bindings, one that ignores undecodable records and one that does not, both
         * MANUAL with RETRY errors on a 30-second backoff, and deploys a consumer for each.
         *
         * @param vertx the Vert.x instance
         * @param onIgnored the callback of the ignoring binding
         * @param ignoringHandler the handler of the ignoring binding
         * @param plainHandler the handler of the other binding
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static DecodeRig open(
                Vertx vertx,
                Runnable onIgnored,
                KafkaRecordHandler<TestEvent> ignoringHandler,
                KafkaRecordHandler<TestEvent> plainHandler)
                throws Exception {
            return open(vertx, onIgnored, ignoringHandler, plainHandler, null);
        }

        /**
         * Builds the two bindings as {@link #open(Vertx, Runnable, KafkaRecordHandler, KafkaRecordHandler)}
         * does, with a custom deserializer on the ignoring binding.
         *
         * @param vertx the Vert.x instance
         * @param onIgnored the callback of the ignoring binding
         * @param ignoringHandler the handler of the ignoring binding
         * @param plainHandler the handler of the other binding
         * @param ignoringDeserializer the deserializer of the ignoring binding, or {@code null} for the framework's
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static DecodeRig open(
                Vertx vertx,
                Runnable onIgnored,
                KafkaRecordHandler<TestEvent> ignoringHandler,
                KafkaRecordHandler<TestEvent> plainHandler,
                KafkaDeserializer<TestEvent> ignoringDeserializer)
                throws Exception {
            DecodeRig rig = new DecodeRig(vertx);
            try {
                KafkaConsumerBinding.Builder<TestEvent> ignoringBuilder = KafkaConsumerBinding.builder(
                                "decode-ignoring", TestEvent.class)
                        .topic(rig.ignoringTopic)
                        .groupId(rig.ignoringGroup)
                        .commitStrategy(CommitStrategy.MANUAL)
                        .errorStrategy(ErrorStrategy.RETRY)
                        .deadLetterTopic(rig.ignoringDeadLetterTopic)
                        .handler(ignoringHandler)
                        .ignoreUndecodable(onIgnored);
                if (ignoringDeserializer != null) {
                    ignoringBuilder.deserializer(ignoringDeserializer);
                }
                KafkaConsumerBinding<TestEvent> ignoring = ignoringBuilder.build();
                KafkaConsumerBinding<TestEvent> plain = KafkaConsumerBinding.builder("decode-plain", TestEvent.class)
                        .topic(rig.plainTopic)
                        .groupId("it-decode-plain-" + UUID.randomUUID())
                        .commitStrategy(CommitStrategy.MANUAL)
                        .errorStrategy(ErrorStrategy.RETRY)
                        .deadLetterTopic(rig.plainTopic + ".dlq")
                        .handler(plainHandler)
                        .build();
                rig.deploy(Set.of(ignoring, plain));
            } catch (Exception | Error e) {
                rig.close();
                throw e;
            }
            return rig;
        }

        /**
         * Produces one record.
         *
         * @param topic the topic
         * @param value the value bytes
         * @return the broker's metadata for the record
         * @throws Exception if the send fails
         */
        RecordMetadata produce(String topic, byte[] value) throws Exception {
            return producer.send(topic, "k", value, KafkaRecordHeaders.empty())
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
         * Reads a dead-letter topic from the beginning with a throwaway group. The wait for the
         * partition assignment does not count against {@code windowMs}, so an empty result means the
         * reader was in place and saw nothing.
         *
         * @param topic the dead-letter topic
         * @param expected how many records to stop at
         * @param windowMs how long to keep reading once the reader is assigned
         * @return the records read, in order; fewer than {@code expected} when the window ran out
         */
        List<ConsumerRecord<String, byte[]>> readDeadLetters(String topic, int expected, long windowMs) {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-decode-reader-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(props)) {
                reader.subscribe(List.of(topic));
                long assignDeadline = System.currentTimeMillis() + 15_000L;
                while (reader.assignment().isEmpty() && System.currentTimeMillis() < assignDeadline) {
                    reader.poll(Duration.ofMillis(200)).forEach(records::add);
                }
                long deadline = System.currentTimeMillis() + windowMs;
                while (records.size() < expected && System.currentTimeMillis() < deadline) {
                    reader.poll(Duration.ofMillis(200)).forEach(records::add);
                }
            }
            return records;
        }

        /** Undeploys every consumer, then closes the producer and the admin client. */
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

        private void deploy(Set<KafkaConsumerBinding<?>> bindings) throws Exception {
            registerCodec("dispatch.envelope");
            registerCodec("dispatch.result");

            // Start each consumer at the beginning of its topic, with a 30-second backoff before any retry
            JsonObject consumers = new JsonObject();
            for (KafkaConsumerBinding<?> binding : bindings) {
                consumers.put(
                        binding.name(),
                        new JsonObject()
                                .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                                .put(
                                        "retry",
                                        new JsonObject()
                                                .put("maxRetries", 3)
                                                .put("backoffMs", 30_000L)
                                                .put("exhaustedStrategy", "DEAD_LETTER")));
            }
            JsonObject config = baseConfig();
            config.getJsonObject("kafka").put("consumers", consumers);

            ServiceContractRegistry serviceRegistry = ServiceContractRegistry.build(Set.of(), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(serviceRegistry);
            KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(
                    bindings,
                    Set.of(),
                    serviceRegistry,
                    resolver,
                    KafkaTestSupport.jsonSerdeRegistry(),
                    KafkaConfig.fromConfig(config, configParser()));

            for (ConsumerEntry entry : registry.entries()) {
                if (entry.config().enabled()) {
                    String id = vertx.deployVerticle(new KafkaConsumerVerticle(
                                    entry,
                                    List.of(interceptor),
                                    producer,
                                    KafkaTestSupport.requestSender(vertx),
                                    resolver,
                                    KafkaTestSupport.eventBusClient(vertx),
                                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                                    KafkaTestSupport.noOpEnvelopeBuilder(),
                                    KafkaTestSupport.jsonSerdeRegistry()))
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(AWAIT_MS, TimeUnit.MILLISECONDS);
                    deploymentIds.add(id);
                }
            }
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
