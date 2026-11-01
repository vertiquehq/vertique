// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.kafka.config.KafkaConfig;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration tests of the settlement protocol of a {@code MANUAL} consumer against a real Kafka broker: what only a
 * real poll loop and a real group coordinator can show.
 *
 * <p>Verified:
 * <ul>
 *   <li>Records that fail while later offsets of the partition succeed are not lost: the group's committed offset stays
 *       below the failed records until they succeed, and then moves past all of them.</li>
 *   <li>A partition that moves to another consumer and comes back to the first, while a handler of the first
 *       assignment still runs, loses no record: every offset is handled at least once and the committed offset
 *       reaches the end.</li>
 *   <li>A consumer that holds every partition, because its handlers are blocked and its retention bound is full, keeps
 *       polling: it is not evicted from its group, which would redeliver records, though the blocking outlasts
 *       {@code max.poll.interval.ms}.</li>
 *   <li>A stop commits only the settled prefix; a restart reads the unfinished record again and commits it once it
 *       finishes.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class KafkaSettlementIT {

    static final KafkaContainer kafka = KafkaTestContainers.shared();

    private static final long AWAIT_MS = 45_000L;

    /** The message payload. */
    record Event(String id, String name) {}

    /** One consumer group over one topic, with the broker-side reads the tests assert on. */
    static final class Rig implements AutoCloseable {

        final String topic = "it.settle." + UUID.randomUUID();
        final String group = "it-settle-" + UUID.randomUUID();
        final int partitions;

        private final Vertx vertx;
        private final KafkaProducerFactory producer;
        private final Admin admin =
                Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()));
        private final List<String> deployments = new CopyOnWriteArrayList<>();
        private final AtomicInteger names = new AtomicInteger();
        private final KafkaProducer<String, byte[]> plain = new KafkaProducer<>(Map.<String, Object>of(
                "bootstrap.servers",
                kafka.getBootstrapServers(),
                "key.serializer",
                StringSerializer.class,
                "value.serializer",
                ByteArraySerializer.class));

        Rig(Vertx vertx, int partitions) throws Exception {
            this.vertx = vertx;
            this.partitions = partitions;
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
            this.producer = new KafkaProducerFactory(
                    vertx,
                    KafkaConfig.fromConfig(baseConfig(), configParser()),
                    KafkaTestSupport.noOpPropagator(),
                    KafkaTestSupport.jsonSerdeRegistry());
        }

        void produce(int partition, String id) throws Exception {
            byte[] value = DatabindCodec.mapper().writeValueAsBytes(new Event(id, id));
            plain.send(new ProducerRecord<>(topic, partition, "k", value)).get(30, TimeUnit.SECONDS);
        }

        /** Deploys a forcing consumer of the group; returns its deployment id. */
        String deploy(KafkaRecordHandler<Event> handler, JsonObject consumerProperties, long drainMs, int maxInFlight)
                throws Exception {
            return deploy(handler, consumerProperties, drainMs, maxInFlight, 4096);
        }

        /** Deploys a consumer with two permits and a retention bound of eight records, so it fills at once. */
        String deployBounded(KafkaRecordHandler<Event> handler, JsonObject consumerProperties) throws Exception {
            return deploy(handler, consumerProperties, 1_000L, 2, 8);
        }

        private String deploy(
                KafkaRecordHandler<Event> handler,
                JsonObject consumerProperties,
                long drainMs,
                int maxInFlight,
                int maxRetainedRecords)
                throws Exception {
            return deploy(handler, consumerProperties, drainMs, maxInFlight, maxRetainedRecords, null);
        }

        /** Deploys a forcing consumer whose binding asks the gate before it starts a record. */
        String deployGated(KafkaRecordHandler<Event> handler, JsonObject consumerProperties, KafkaDispatchGate gate)
                throws Exception {
            return deploy(handler, consumerProperties, 1_000L, 8, 4096, gate);
        }

        private String deploy(
                KafkaRecordHandler<Event> handler,
                JsonObject consumerProperties,
                long drainMs,
                int maxInFlight,
                int maxRetainedRecords,
                KafkaDispatchGate gate)
                throws Exception {
            String name = "settle-" + names.incrementAndGet();
            KafkaConsumerBinding.Builder<Event> builder = KafkaConsumerBinding.builder(name, Event.class)
                    .topic(topic)
                    .groupId(group)
                    .commitStrategy(CommitStrategy.MANUAL)
                    .errorStrategy(ErrorStrategy.RETRY)
                    .deadLetterTopic(topic + ".dlq")
                    .handler(handler)
                    .forceStrategies();
            if (gate != null) {
                builder.dispatchGate(gate);
            }
            KafkaConsumerBinding<Event> binding = builder.build();
            JsonObject consumer = new JsonObject()
                    .put("maxInFlight", maxInFlight)
                    .put("maxRetainedRecords", maxRetainedRecords)
                    .put(
                            "retry",
                            new JsonObject()
                                    .put("maxRetries", 5)
                                    .put("backoffMs", 200)
                                    .put("maxBackoffMs", 400)
                                    .put("exhaustedStrategy", "DEAD_LETTER"))
                    .put("properties", consumerProperties.copy().put("auto.offset.reset", "earliest"));
            JsonObject config = baseConfig();
            config.getJsonObject("kafka").put("consumers", new JsonObject().put(name, consumer));
            try {
                vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
                vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
            } catch (IllegalStateException ignored) {
                // Already registered
            }
            ServiceContractRegistry services = ServiceContractRegistry.build(Set.of(), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(services);
            KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(
                    Set.<KafkaConsumerBinding<?>>of(binding),
                    Set.of(),
                    services,
                    resolver,
                    KafkaTestSupport.jsonSerdeRegistry(),
                    KafkaConfig.fromConfig(config, configParser()));
            ConsumerEntry entry = registry.entries().iterator().next();
            String id = vertx.deployVerticle(new KafkaConsumerVerticle(
                            entry,
                            List.of(),
                            producer,
                            KafkaTestSupport.requestSender(vertx),
                            resolver,
                            KafkaTestSupport.eventBusClient(vertx),
                            KafkaTestSupport.noOpInboundExecutionContextScope(),
                            KafkaTestSupport.noOpEnvelopeBuilder(),
                            KafkaTestSupport.jsonSerdeRegistry(),
                            drainMs,
                            10_000L))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(AWAIT_MS, TimeUnit.MILLISECONDS);
            deployments.add(id);
            return id;
        }

        void undeploy(String id) throws Exception {
            deployments.remove(id);
            try {
                vertx.undeploy(id).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException timedOut) {
                // A stop past its drain deadline reports the records it left; the test asserts on the broker
            }
        }

        /** The committed offset of a partition, or {@code -1} when the group committed none. */
        long committed(int partition) throws Exception {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(new TopicPartition(topic, partition));
            return committed == null ? -1L : committed.offset();
        }

        /** The offset after the last record of a partition. */
        long endOffset(int partition) throws Exception {
            TopicPartition tp = new TopicPartition(topic, partition);
            return admin.listOffsets(Map.of(tp, org.apache.kafka.clients.admin.OffsetSpec.latest()))
                    .partitionResult(tp)
                    .get(10, TimeUnit.SECONDS)
                    .offset();
        }

        long awaitCommitted(int partition, long expected) throws Exception {
            long deadline = System.currentTimeMillis() + AWAIT_MS;
            long last = committed(partition);
            while (last != expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                last = committed(partition);
            }
            return last;
        }

        @Override
        public void close() {
            for (String id : deployments) {
                try {
                    vertx.undeploy(id).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Best effort: the broker outlives the test
                }
            }
            try {
                producer.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Best effort
            }
            plain.close(Duration.ofSeconds(5));
            admin.close(Duration.ofSeconds(5));
        }

        private static JsonObject baseConfig() {
            return new JsonObject()
                    .put("kafka", new JsonObject().put("bootstrap.servers", kafka.getBootstrapServers()));
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "not reached within " + AWAIT_MS + " ms: " + what);
    }

    @Test
    @DisplayName("records that fail while later offsets succeed are not committed past, and none is lost")
    void failedEarlyRecordsHoldTheCommit(Vertx vertx) throws Exception {
        try (Rig rig = new Rig(vertx, 1)) {
            // Given: a handler that fails ids e0 and e1 once, then holds their retries until released
            CountDownLatch release = new CountDownLatch(1);
            Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
            Set<String> succeeded = Collections.newSetFromMap(new ConcurrentHashMap<>());
            rig.deploy(
                    message -> {
                        String id = message.value().id();
                        int attempt = attempts.computeIfAbsent(id, k -> new AtomicInteger())
                                .incrementAndGet();
                        boolean early = id.equals("e0") || id.equals("e1");
                        if (early && attempt == 1) {
                            return Future.failedFuture(new IllegalStateException("first attempt"));
                        }
                        Promise<Void> done = Promise.promise();
                        if (early) {
                            vertx.executeBlocking(() -> {
                                        release.await();
                                        return null;
                                    })
                                    .onComplete(r -> {
                                        succeeded.add(id);
                                        done.complete();
                                    });
                        } else {
                            succeeded.add(id);
                            done.complete();
                        }
                        return done.future();
                    },
                    new JsonObject(),
                    2_000L,
                    8);
            for (String id : List.of("e0", "e1", "l2", "l3", "l4", "l5")) {
                rig.produce(0, id);
            }

            // When: the later offsets have all succeeded while the early ones are held
            await(() -> succeeded.containsAll(Set.of("l2", "l3", "l4", "l5")), "the later records succeed");
            Thread.sleep(2_000);

            // Then: the group committed nothing past the earlier records
            assertEquals(-1L, rig.committed(0), "no offset may be committed past an unfinished record");

            // And: once they finish, the whole prefix is committed
            release.countDown();
            assertEquals(6L, rig.awaitCommitted(0, 6L));
            assertEquals(Set.of("e0", "e1", "l2", "l3", "l4", "l5"), succeeded);
        }
    }

    @Test
    @DisplayName("a partition that moves away and comes back to the same consumer loses no record")
    void revokeAndReassignToTheSameConsumer(Vertx vertx) throws Exception {
        try (Rig rig = new Rig(vertx, 2)) {
            // Given a first consumer that owns both partitions, and a handler that holds one record on each, so that
            // whichever partition moves away leaves a handler of the old assignment running
            Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
            CountDownLatch release = new CountDownLatch(1);
            KafkaRecordHandler<Event> first = message -> {
                String id = message.value().id();
                handled.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                if (id.startsWith("held")) {
                    Promise<Void> done = Promise.promise();
                    vertx.executeBlocking(() -> {
                                release.await();
                                return null;
                            })
                            .onComplete(r -> done.complete());
                    return done.future();
                }
                return Future.succeededFuture();
            };
            JsonObject fast = new JsonObject().put("session.timeout.ms", "6000").put("heartbeat.interval.ms", "1000");
            rig.deploy(first, fast, 1_000L, 16);
            rig.produce(0, "held0");
            rig.produce(1, "held1");
            await(() -> handled.containsKey("held0") && handled.containsKey("held1"), "both records are held");

            // When a second consumer joins: the partitions it takes are revoked from the first
            Map<Integer, Set<String>> secondHandled = new ConcurrentHashMap<>();
            String second = rig.deploy(
                    message -> {
                        secondHandled
                                .computeIfAbsent(message.partition(), k -> ConcurrentHashMap.newKeySet())
                                .add(message.value().id());
                        return Future.succeededFuture();
                    },
                    fast,
                    1_000L,
                    16);
            await(
                    () -> {
                        try {
                            rig.produce(0, "during0-" + System.nanoTime());
                            rig.produce(1, "during1-" + System.nanoTime());
                            Thread.sleep(300);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return !secondHandled.isEmpty();
                    },
                    "the second consumer is given a partition");
            Set<Integer> moved = Set.copyOf(secondHandled.keySet());
            assertFalse(moved.isEmpty(), "a partition was revoked from the first consumer");

            // And: the second consumer leaves, so the partitions come back to the first
            rig.undeploy(second);
            await(
                    () -> {
                        try {
                            for (int partition : moved) {
                                rig.produce(partition, "probe" + partition + "-" + System.nanoTime());
                            }
                            Thread.sleep(300);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return moved.stream().allMatch(partition -> handled.keySet().stream()
                                .anyMatch(id -> id.startsWith("probe" + partition + "-")));
                    },
                    "the first consumer is given the partitions back and handles records produced after");

            // And: the handlers of the old assignment finish after the partitions came back
            release.countDown();

            // Then nothing is lost: the committed offsets reach the end of both partitions
            long end0 = rig.endOffset(0);
            long end1 = rig.endOffset(1);
            assertEquals(end0, rig.awaitCommitted(0, end0));
            assertEquals(end1, rig.awaitCommitted(1, end1));
            assertTrue(handled.containsKey("held0") && handled.containsKey("held1"));
        }
    }

    @Test
    @DisplayName("a consumer that holds every partition keeps polling and is not evicted from its group")
    void heldPartitionsDoNotEvictTheConsumer(Vertx vertx) throws Exception {
        try (Rig rig = new Rig(vertx, 2)) {
            // Given: handlers that block, a retention bound that fills at once, and a poll interval of three seconds
            Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
            CountDownLatch release = new CountDownLatch(1);
            JsonObject shortPoll = new JsonObject()
                    .put("max.poll.interval.ms", "3000")
                    .put("session.timeout.ms", "6000")
                    .put("heartbeat.interval.ms", "1000");
            rig.deployBounded(
                    message -> {
                        handled.computeIfAbsent(message.value().id(), k -> new AtomicInteger())
                                .incrementAndGet();
                        Promise<Void> done = Promise.promise();
                        vertx.executeBlocking(() -> {
                                    release.await();
                                    return null;
                                })
                                .onComplete(r -> done.complete());
                        return done.future();
                    },
                    shortPoll);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String id = "r" + i;
                ids.add(id);
                rig.produce(i % 2, id);
            }
            await(() -> handled.size() >= 2, "the first records are running");

            // When: the blocking outlasts several poll intervals
            Thread.sleep(9_000);
            release.countDown();

            // Then: every record is handled, none twice, because a rebalance would have delivered some again
            await(() -> handled.size() == 20, "all records handled");
            Thread.sleep(1_000);
            for (String id : ids) {
                assertEquals(1, handled.get(id).get(), "record " + id + " must be delivered once");
            }
            assertEquals(10L, rig.awaitCommitted(0, 10L));
            assertEquals(10L, rig.awaitCommitted(1, 10L));
        }
    }

    @Test
    @DisplayName("a closed gate holds the records and the fetching without leaving the group, and no retry is spent")
    void closedGateHoldsRecordsWithoutEviction(Vertx vertx) throws Exception {
        try (Rig rig = new Rig(vertx, 2)) {
            // Given: a gate that is closed, and a short poll interval
            java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(false);
            Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
            JsonObject shortPoll = new JsonObject()
                    .put("max.poll.interval.ms", "3000")
                    .put("session.timeout.ms", "6000")
                    .put("heartbeat.interval.ms", "1000");
            rig.deployGated(
                    message -> {
                        handled.computeIfAbsent(message.value().id(), k -> new AtomicInteger())
                                .incrementAndGet();
                        return Future.succeededFuture();
                    },
                    shortPoll,
                    new KafkaDispatchGate() {
                        @Override
                        public boolean open() {
                            return open.get();
                        }

                        @Override
                        public long recheckMs() {
                            return 100L;
                        }
                    });
            for (int i = 0; i < 6; i++) {
                rig.produce(i % 2, "g" + i);
            }

            // When: the gate stays closed for three poll intervals
            Thread.sleep(9_000);
            assertEquals(0, handled.size(), "no record starts while the gate is closed");
            assertEquals(-1L, rig.committed(0));

            // And: it opens
            open.set(true);

            // Then: every record runs once, so the consumer kept its place in the group
            await(() -> handled.size() == 6, "all records handled");
            Thread.sleep(1_000);
            for (int i = 0; i < 6; i++) {
                assertEquals(1, handled.get("g" + i).get(), "record g" + i + " must run once");
            }
            assertEquals(3L, rig.awaitCommitted(0, 3L));
            assertEquals(3L, rig.awaitCommitted(1, 3L));
        }
    }

    @Test
    @DisplayName("a stop commits the settled prefix, and a restart reads the unfinished record again")
    void stopAndRestart(Vertx vertx) throws Exception {
        try (Rig rig = new Rig(vertx, 1)) {
            // Given: a consumer whose handler never finishes record s2
            Set<String> first = Collections.newSetFromMap(new ConcurrentHashMap<>());
            String id = rig.deploy(
                    message -> {
                        String name = message.value().id();
                        first.add(name);
                        return name.equals("s2") ? Promise.<Void>promise().future() : Future.succeededFuture();
                    },
                    new JsonObject(),
                    500L,
                    8);
            for (String name : List.of("s0", "s1", "s2", "s3")) {
                rig.produce(0, name);
            }
            await(() -> first.containsAll(Set.of("s0", "s1", "s2", "s3")), "all four started");
            assertEquals(2L, rig.awaitCommitted(0, 2L), "s3 finished, but s2 holds the prefix at 2");

            // When: the consumer stops past its drain deadline
            rig.undeploy(id);

            // Then: the commit is still the settled prefix
            assertEquals(2L, rig.committed(0));

            // And: a new consumer reads s2 and s3 again and commits them
            Set<String> second = Collections.newSetFromMap(new ConcurrentHashMap<>());
            rig.deploy(
                    message -> {
                        second.add(message.value().id());
                        return Future.succeededFuture();
                    },
                    new JsonObject(),
                    1_000L,
                    8);
            assertEquals(4L, rig.awaitCommitted(0, 4L));
            assertEquals(Set.of("s2", "s3"), second);
        }
    }
}
