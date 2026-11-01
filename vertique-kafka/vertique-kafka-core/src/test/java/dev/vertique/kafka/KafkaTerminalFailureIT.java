// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests for a handler that fails a record with {@link KafkaTerminalFailure}, against a
 * real Kafka broker.
 *
 * <p>Verified:
 * <ul>
 *   <li>Under {@code DEAD_LETTER} the record goes straight to the dead-letter topic with its
 *       original value and key, the source headers and an error header that holds the error type
 *       alone.</li>
 *   <li>Under {@code SKIP} the record is skipped with a log that names the consumer and the error
 *       type, and the dead-letter topic stays empty.</li>
 *   <li>A forcing binding handles records of one partition concurrently: two records that fail
 *       together are both run again, and none is lost.</li>
 *   <li>Neither path retries, and neither pauses the consumer: the next record is handled long
 *       before the configured backoff would end. Nothing the error handler logs holds the record's
 *       value.</li>
 * </ul>
 *
 * <p>The class timeout is longer than the standard 20 seconds because the broker may be starting
 * and the tests wait on a 30-second backoff they expect not to enter. Each test owns its topics and
 * groups; a {@code TerminalRig} builds the binding, the interceptor and the log capture, and
 * releases every resource in {@code finally}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class KafkaTerminalFailureIT {

    // --- Testcontainers ---

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    private static final long AWAIT_MS = 30_000L;

    /** How long the second record may take once the first one is settled; far inside the backoff. */
    private static final long NOT_PAUSED_MS = 5_000L;

    /** Part of the first record's value that must never reach a header or a log. */
    private static final String SECRET = "secret-7";

    private static final String ERROR_TYPE = "AmbiguousSignal";

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

    /** Keeps every event the error handler logs, safely readable from the test thread. */
    static final class LogCapture extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }

        /**
         * Renders every logged event as the text a reader of the log would see: the formatted
         * message and the message of the throwable and each cause.
         *
         * @return one string per logged event
         */
        List<String> rendered() {
            List<String> rendered = new ArrayList<>();
            for (ILoggingEvent event : events) {
                StringBuilder text = new StringBuilder(event.getFormattedMessage());
                for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                    text.append(' ').append(proxy.getMessage());
                }
                rendered.add(text.toString());
            }
            return rendered;
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a terminal failure goes straight to the dead-letter topic without retry or pause")
    void terminalFailureGoesStraightToTheDeadLetterTopicWithoutRetryOrPause(Vertx vertx) throws Exception {
        // Given: a forcing MANUAL RETRY binding on a 30-second backoff that dead-letters on exhaustion,
        // and a handler that fails the first record terminally and accepts the second
        CompletableFuture<Void> secondHandled = new CompletableFuture<>();
        TerminalRig rig = TerminalRig.open(vertx, "terminal-dlq", true, ErrorStrategy.DEAD_LETTER, message -> {
            if (message.value().name().contains(SECRET)) {
                return Future.failedFuture(new KafkaTerminalFailure(ERROR_TYPE));
            }
            secondHandled.complete(null);
            return Future.succeededFuture();
        });
        try {
            // When: the first record, then the second, are produced
            TestEvent firstEvent = new TestEvent("evt-1", "holds " + SECRET);
            RecordMetadata first = rig.produce("k-1", firstEvent);
            RecordMetadata second = rig.produce("k-2", new TestEvent("evt-2", "fine"));
            awaitTrue(() ->
                    !rig.interceptor.outcomesAt(rig.topic, first.getOffset()).isEmpty());

            // Then: the first record was dead-lettered at once and never scheduled for a retry
            assertEquals(
                    List.of(KafkaTerminalOutcome.DLQ_PUBLISHED),
                    rig.interceptor.outcomesAt(rig.topic, first.getOffset()));

            // And: the second record is handled far inside the 30-second backoff, so the consumer was not paused
            assertTrue(
                    handledWithin(secondHandled, NOT_PAUSED_MS),
                    "the second record should be handled within " + NOT_PAUSED_MS
                            + " ms, so the consumer was not paused");

            // And: the dead-letter topic holds the first record's original value and key with the source headers
            OptionalLong committed = rig.awaitCommittedOffset(second.getOffset() + 1);
            List<ConsumerRecord<String, byte[]>> deadLetters = rig.readDeadLetters(1, AWAIT_MS);
            assertEquals(1, deadLetters.size(), "the dead-letter topic should hold exactly the first record");
            ConsumerRecord<String, byte[]> deadLetter = deadLetters.get(0);
            assertEquals("k-1", deadLetter.key());
            assertArrayEquals(TerminalRig.encode(firstEvent), deadLetter.value());
            assertEquals(rig.topic, headerText(deadLetter, KafkaDlqHeaders.SOURCE_TOPIC));
            assertEquals(
                    String.valueOf(first.getPartition()), headerText(deadLetter, KafkaDlqHeaders.SOURCE_PARTITION));
            assertEquals(String.valueOf(first.getOffset()), headerText(deadLetter, KafkaDlqHeaders.SOURCE_OFFSET));

            // And: the error header holds the error type alone, once
            assertEquals(
                    List.of(ERROR_TYPE),
                    headerTexts(deadLetter, KafkaDlqHeaders.ERROR),
                    "x-dlq-error should hold the error type alone");

            // And: neither a header nor a log record of the error handler holds the record's value
            for (Header header : deadLetter.headers()) {
                assertFalse(
                        new String(header.value(), StandardCharsets.UTF_8).contains(SECRET),
                        "header " + header.key() + " must not hold the record's value");
            }
            for (String logged : rig.log.rendered()) {
                assertFalse(logged.contains(SECRET), "the error handler must not log the record's value: " + logged);
            }

            // And: the offset is committed past both records
            assertEquals(OptionalLong.of(second.getOffset() + 1), committed);
        } finally {
            rig.close();
        }
    }

    @Test
    @DisplayName("a terminal failure under SKIP is skipped with a log and no dead letter")
    void terminalFailureUnderSkipIsSkippedWithALogAndNoDeadLetter(Vertx vertx) throws Exception {
        // Given: a binding that does not require its strategies, MANUAL RETRY on a 30-second backoff that
        // skips on exhaustion, and a handler that fails the first record terminally and accepts the second
        CompletableFuture<Void> secondHandled = new CompletableFuture<>();
        TerminalRig rig = TerminalRig.open(vertx, "terminal-skip", false, ErrorStrategy.SKIP, message -> {
            if (message.value().name().contains(SECRET)) {
                return Future.failedFuture(new KafkaTerminalFailure(ERROR_TYPE));
            }
            secondHandled.complete(null);
            return Future.succeededFuture();
        });
        try {
            // When: the first record, then the second, are produced
            RecordMetadata first = rig.produce("k-1", new TestEvent("evt-1", "holds " + SECRET));
            RecordMetadata second = rig.produce("k-2", new TestEvent("evt-2", "fine"));
            awaitTrue(() ->
                    !rig.interceptor.outcomesAt(rig.topic, first.getOffset()).isEmpty());

            // Then: the first record was skipped and never scheduled for a retry or dead-lettered
            assertEquals(List.of(KafkaTerminalOutcome.SKIP), rig.interceptor.outcomesAt(rig.topic, first.getOffset()));

            // And: the second record is handled far inside the 30-second backoff, so the consumer was not paused
            assertTrue(
                    handledWithin(secondHandled, NOT_PAUSED_MS),
                    "the second record should be handled within " + NOT_PAUSED_MS
                            + " ms, so the consumer was not paused");

            // And: the offset is committed past both records
            assertEquals(OptionalLong.of(second.getOffset() + 1), rig.awaitCommittedOffset(second.getOffset() + 1));

            // And: the dead-letter topic holds nothing
            List<ConsumerRecord<String, byte[]>> deadLetters = rig.readDeadLetters(1, 3_000L);
            assertTrue(
                    deadLetters.isEmpty(), "the dead-letter topic should hold nothing but held " + deadLetters.size());

            // And: one log record names the consumer and the error type, and none holds the record's value
            List<String> rendered = rig.log.rendered();
            long naming = rendered.stream()
                    .filter(text -> text.contains(rig.consumerName) && text.contains(ERROR_TYPE))
                    .count();
            assertEquals(1, naming, "exactly one log record should name the consumer and the error type: " + rendered);
            for (String logged : rendered) {
                assertFalse(logged.contains(SECRET), "the error handler must not log the record's value: " + logged);
            }
        } finally {
            rig.close();
        }
    }

    @Test
    @DisplayName("two records of one partition that fail together are both run again and none is lost")
    void twoRecordsOfOnePartitionThatFailTogetherAreBothDeliveredAgain(Vertx vertx) throws Exception {
        // Given: a forcing binding on a short backoff, and a handler that fails each record once after a delay
        // long enough for both to be in flight together if the binding allowed it
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostRunning = new AtomicInteger();
        Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        List<String> succeeded = Collections.synchronizedList(new ArrayList<>());
        TerminalRig rig = TerminalRig.open(vertx, "terminal-pair", true, ErrorStrategy.DEAD_LETTER, 200L, message -> {
            String id = message.value().id();
            mostRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            int attempt =
                    attempts.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            Promise<Void> result = Promise.promise();
            vertx.setTimer(300, timer -> {
                running.decrementAndGet();
                if (attempt == 1) {
                    result.fail(new RuntimeException("first attempt of " + id));
                } else {
                    succeeded.add(id);
                    result.complete();
                }
            });
            return result.future();
        });
        try {
            // When: two records are produced to the same partition
            RecordMetadata first = rig.produce("k-1", new TestEvent("evt-1", "one"));
            RecordMetadata second = rig.produce("k-2", new TestEvent("evt-2", "two"));
            assertEquals(first.getPartition(), second.getPartition(), "the records should share a partition");

            // Then: the offset is committed past both, and both succeeded on their second attempt
            assertEquals(OptionalLong.of(second.getOffset() + 1), rig.awaitCommittedOffset(second.getOffset() + 1));
            assertEquals(List.of("evt-1", "evt-2"), List.copyOf(succeeded));
            assertTrue(attempts.get("evt-1").get() >= 2, "the first record should be delivered again");
            assertTrue(attempts.get("evt-2").get() >= 2, "the second record should be delivered again");

            // And: the binding ran both records at once, and nothing was dead-lettered
            assertEquals(2, mostRunning.get(), "a forcing binding should run records of a partition concurrently");
            assertTrue(rig.readDeadLetters(1, 3_000L).isEmpty(), "nothing should be dead-lettered");
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

    private static boolean handledWithin(CompletableFuture<Void> handled, long windowMs) throws Exception {
        try {
            handled.get(windowMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private static List<String> headerTexts(ConsumerRecord<String, byte[]> record, String key) {
        List<String> texts = new ArrayList<>();
        for (Header header : record.headers().headers(key)) {
            texts.add(new String(header.value(), StandardCharsets.UTF_8));
        }
        return texts;
    }

    private static String headerText(ConsumerRecord<String, byte[]> record, String key) {
        List<String> texts = headerTexts(record, key);
        assertEquals(1, texts.size(), "expected exactly one " + key + " header but found " + texts);
        return texts.get(0);
    }

    /**
     * Deploys the consumer of one test and offers the broker-side reads the test asserts on:
     * producing a record, the group's committed offset and the records of the dead-letter topic. It
     * owns the recording interceptor and the capture of what the error handler logs.
     * {@link #close()} undeploys the consumer, closes the producer and the admin client and detaches
     * the log capture.
     */
    static final class TerminalRig {

        final String consumerName;
        final String topic;
        final String group;
        final String deadLetterTopic;
        final RecordingInterceptor interceptor = new RecordingInterceptor();
        final LogCapture log = new LogCapture();

        private final Vertx vertx;
        private final KafkaProducerFactory producer;
        private final Admin admin;
        private final Logger errorHandlerLogger = (Logger) LoggerFactory.getLogger(KafkaErrorHandler.class);
        private final List<String> deploymentIds = new ArrayList<>();

        private TerminalRig(Vertx vertx, String consumerName) {
            this.vertx = vertx;
            this.consumerName = consumerName;
            String suffix = UUID.randomUUID().toString();
            this.topic = "it." + consumerName + "." + suffix;
            this.group = "it-" + consumerName + "-" + suffix;
            this.deadLetterTopic = topic + ".dlq";
            KafkaConfig config = KafkaConfig.fromConfig(baseConfig(), configParser());
            this.producer = new KafkaProducerFactory(
                    vertx, config, KafkaTestSupport.noOpPropagator(), KafkaTestSupport.jsonSerdeRegistry());
            this.admin = Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()));
            this.log.setContext(errorHandlerLogger.getLoggerContext());
            this.log.start();
            this.errorHandlerLogger.addAppender(log);
        }

        /**
         * Builds a MANUAL, RETRY handler binding on its own topic and group, configures its retry
         * with three retries on a 30-second backoff and the given exhausted strategy, and deploys it.
         *
         * @param vertx the Vert.x instance
         * @param consumerName the binding's name, which is also its consumer name in logs
         * @param force whether the binding requires its strategies
         * @param exhausted the exhausted strategy the configuration states
         * @param handler the binding's handler
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static TerminalRig open(
                Vertx vertx,
                String consumerName,
                boolean force,
                ErrorStrategy exhausted,
                KafkaRecordHandler<TestEvent> handler)
                throws Exception {
            return open(vertx, consumerName, force, exhausted, 30_000L, handler);
        }

        /**
         * Builds and deploys the binding as {@link #open(Vertx, String, boolean, ErrorStrategy, KafkaRecordHandler)}
         * does, with the given backoff.
         *
         * @param vertx the Vert.x instance
         * @param consumerName the binding's name, which is also its consumer name in logs
         * @param force whether the binding requires its strategies
         * @param exhausted the exhausted strategy the configuration states
         * @param backoffMs the retry backoff in milliseconds
         * @param handler the binding's handler
         * @return the running rig
         * @throws Exception if the deployment fails
         */
        static TerminalRig open(
                Vertx vertx,
                String consumerName,
                boolean force,
                ErrorStrategy exhausted,
                long backoffMs,
                KafkaRecordHandler<TestEvent> handler)
                throws Exception {
            TerminalRig rig = new TerminalRig(vertx, consumerName);
            try {
                KafkaConsumerBinding.Builder<TestEvent> builder = KafkaConsumerBinding.builder(
                                consumerName, TestEvent.class)
                        .topic(rig.topic)
                        .groupId(rig.group)
                        .commitStrategy(CommitStrategy.MANUAL)
                        .errorStrategy(ErrorStrategy.RETRY)
                        .deadLetterTopic(rig.deadLetterTopic)
                        .handler(handler);
                if (force) {
                    builder.requireBindingStrategies();
                }
                JsonObject retry = new JsonObject()
                        .put(
                                "retry",
                                new JsonObject()
                                        .put("maxRetries", 3)
                                        .put("backoffMs", backoffMs)
                                        .put("exhaustedStrategy", exhausted.name()));
                rig.deploy(builder.build(), retry);
            } catch (Exception | Error e) {
                rig.close();
                throw e;
            }
            return rig;
        }

        /**
         * Encodes an event as the JSON bytes the rig produces.
         *
         * @param event the event
         * @return its JSON bytes
         * @throws Exception if the event cannot be encoded
         */
        static byte[] encode(TestEvent event) throws Exception {
            return DatabindCodec.mapper().writeValueAsBytes(event);
        }

        /**
         * Produces one JSON record to the consumer's topic.
         *
         * @param key the record key
         * @param event the value, encoded as JSON
         * @return the broker's metadata for the record
         * @throws Exception if the send fails
         */
        RecordMetadata produce(String key, TestEvent event) throws Exception {
            return producer.send(topic, key, encode(event), KafkaRecordHeaders.empty())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(AWAIT_MS, TimeUnit.MILLISECONDS);
        }

        /**
         * Waits until the group's committed offset on the topic equals {@code expected}, or the wait
         * runs out.
         *
         * @param expected the offset to wait for
         * @return the last offset read, empty when the group committed nothing
         * @throws Exception if the broker cannot be asked
         */
        OptionalLong awaitCommittedOffset(long expected) throws Exception {
            long deadline = System.currentTimeMillis() + AWAIT_MS;
            OptionalLong last = committedOffset();
            while (!last.equals(OptionalLong.of(expected)) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                last = committedOffset();
            }
            return last;
        }

        /**
         * Reads the dead-letter topic from the beginning with a throwaway group. The wait for the
         * partition assignment does not count against {@code windowMs}, so an empty result means the
         * reader was in place and saw nothing.
         *
         * @param expected how many records to stop at
         * @param windowMs how long to keep reading once the reader is assigned
         * @return the records read, in order; fewer than {@code expected} when the window ran out
         */
        List<ConsumerRecord<String, byte[]>> readDeadLetters(int expected, long windowMs) {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-terminal-reader-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(props)) {
                reader.subscribe(List.of(deadLetterTopic));
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

        /** Undeploys the consumer, closes the producer and the admin client and detaches the log capture. */
        void close() {
            errorHandlerLogger.detachAppender(log);
            log.stop();
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

        private OptionalLong committedOffset() throws Exception {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            return offsets.entrySet().stream()
                    .filter(entry -> entry.getKey().topic().equals(topic) && entry.getValue() != null)
                    .mapToLong(entry -> entry.getValue().offset())
                    .max();
        }

        private void deploy(KafkaConsumerBinding<TestEvent> binding, JsonObject consumerConfig) throws Exception {
            registerCodec("dispatch.envelope");
            registerCodec("dispatch.result");

            // Start the consumer at the beginning of its topic, so no record races the assignment
            JsonObject consumer = new JsonObject()
                    .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                    .mergeIn(consumerConfig);
            JsonObject config = baseConfig();
            config.getJsonObject("kafka").put("consumers", new JsonObject().put(binding.name(), consumer));

            ServiceContractRegistry serviceRegistry = ServiceContractRegistry.build(Set.of(), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(serviceRegistry);
            KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(
                    Set.<KafkaConsumerBinding<?>>of(binding),
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
