// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaDlqHeaders;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceRefusal;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * One scenario of the Kafka signal source proofs: a node (see {@link SourceNode}) over the shared Kafka broker and the
 * shared PostgreSQL engine, with its own topics, consumer groups, consumer names and workflow instances, so that no
 * two proofs meet.
 *
 * <p>A proof declares its sources with {@link #feed}, starts the node with {@link #start()}, starts the instances that
 * wait with {@link #waitingOrder} and {@link #waitingReturn}, produces records through a {@link Feed}, and reads what
 * happened: the completions of the consumer ({@link #completions()}), the events of the delivery bean
 * ({@link #observer()}), the dead-letter topic ({@link Feed#dlq()}) and the rows the workflow wrote
 * ({@link #commands}, {@link #dedupRows}, {@link #history}, {@link #instance}). {@link #close()} stops the consumers
 * and the executor, closes the pools and the clients, and deletes the groups.
 */
final class SourceScenario implements AutoCloseable {

    /** The name of the signal of {@link OrdersContract}. */
    static final String SHIPMENT_DELIVERED = "shipment-delivered";

    /** The name of the signal of {@link ReturnsContract}. */
    static final String PARCEL_DELIVERED = "parcel-delivered";

    /** The timeout of a wait the proofs expect to end at once. */
    static final Duration PROMPT = Duration.ofSeconds(5);

    private static final Duration AWAIT = Duration.ofSeconds(30);
    private static final Duration IDLE = Duration.ofSeconds(20);
    private static final String EXECUTOR = "workflow-executor";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    // --- the workflows the scenario starts ---

    /** The contract of {@link OrderShipping}. */
    @WorkflowContract("orders")
    interface OrdersContract {
        @WorkflowSignal(SHIPMENT_DELIVERED)
        Future<Void> shipmentDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /** The contract of {@link ReturnShipping}. */
    @WorkflowContract("returns")
    interface ReturnsContract {
        @WorkflowSignal(PARCEL_DELIVERED)
        Future<Void> parcelDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /**
     * What a delivery signal carries.
     *
     * @param deliveredAt when the parcel was delivered, as text
     * @param note a free note
     */
    record DeliveryConfirmed(
            @Nullable String deliveredAt, @Nullable String note) {

        static DeliveryConfirmed at(String deliveredAt) {
            return new DeliveryConfirmed(deliveredAt, null);
        }

        static DeliveryConfirmed noting(String note) {
            return new DeliveryConfirmed(null, note);
        }
    }

    /**
     * The message type of the carriers' topics.
     *
     * @param eventId the id of the event
     * @param type the type of the event
     * @param carrierRef the reference the carrier delivered under
     * @param carrier the carrier's name
     * @param weightGrams the parcel's weight
     * @param deliveredAt when the parcel was delivered, as text
     * @param note a free note
     */
    record ShipmentDelivered(
            String eventId,
            String type,
            String carrierRef,
            String carrier,
            int weightGrams,
            String deliveredAt,
            String note) {

        static final String DELIVERED_AT = "2026-03-01T10:00:00Z";
        static final String CARRIER = "ACME-CARRIER-X";
        static final int WEIGHT = 98765;

        ShipmentDelivered withType(String type) {
            return new ShipmentDelivered(eventId, type, carrierRef, carrier, weightGrams, deliveredAt, note);
        }

        ShipmentDelivered withCarrierRef(String carrierRef) {
            return new ShipmentDelivered(eventId, type, carrierRef, carrier, weightGrams, deliveredAt, note);
        }

        ShipmentDelivered withNote(String note) {
            return new ShipmentDelivered(eventId, type, carrierRef, carrier, weightGrams, deliveredAt, note);
        }
    }

    /**
     * The start payload of both workflows.
     *
     * @param carrierRef the reference the workflow waits for
     */
    record StartShipping(String carrierRef) {}

    /**
     * The state of both workflows.
     *
     * @param carrierRef the reference the workflow waits for
     * @param deliveredAt when the parcel was delivered, once the signal applied
     */
    record State(String carrierRef, @Nullable String deliveredAt) {}

    /** Waits for {@code shipment-delivered} on its carrier reference. */
    static final class OrderShipping implements WorkflowDefinition<State, OrdersContract> {

        static final String ID = "order-shipping";

        @Override
        public Class<OrdersContract> contract() {
            return OrdersContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return ID;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartShipping.class, start -> new State(start.carrierRef(), null));
            wf.initialStep("wait-delivery");
            wf.waitForSignal("wait-delivery", SHIPMENT_DELIVERED, DeliveryConfirmed.class)
                    .correlateBy(State::carrierRef)
                    .onSignal((state, confirmed) -> new State(state.carrierRef(), confirmed.deliveredAt()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        }
    }

    /** Waits for {@code parcel-delivered} on its carrier reference. */
    static final class ReturnShipping implements WorkflowDefinition<State, ReturnsContract> {

        static final String ID = "return-shipping";

        @Override
        public Class<ReturnsContract> contract() {
            return ReturnsContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return ID;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartShipping.class, start -> new State(start.carrierRef(), null));
            wf.initialStep("wait-delivery");
            wf.waitForSignal("wait-delivery", PARCEL_DELIVERED, DeliveryConfirmed.class)
                    .correlateBy(State::carrierRef)
                    .onSignal((state, confirmed) -> new State(state.carrierRef(), confirmed.deliveredAt()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        }
    }

    // --- what the scenario reports ---

    /**
     * Where a produced record sits.
     *
     * @param topic the topic
     * @param partition the partition
     * @param offset the offset
     */
    record Produced(String topic, int partition, long offset) {}

    /**
     * An instance that waits for a delivery.
     *
     * @param id the instance id
     * @param scope the scope it started in
     * @param key the carrier reference it waits on
     */
    record Waiting(WorkflowInstanceId id, String scope, String key) {

        UUID uuid() {
            return id.value();
        }
    }

    /**
     * A {@code SIGNAL} command of an instance, as {@code workflow_commands} holds it.
     *
     * @param id the command id
     * @param state the command's state
     * @param actorKind the kind of the actor
     * @param actorId the id of a user or service actor
     * @param actorReason the reason of a system actor
     * @param attribution the attribution status
     * @param dedupKey the dedup key stored on the command
     * @param payload the stored payload, or {@code null}
     * @param context the stored context, or {@code null}
     * @param provenance the stored provenance, or {@code null}
     */
    record CommandRow(
            long id,
            String state,
            @Nullable String actorKind,
            @Nullable String actorId,
            @Nullable String actorReason,
            @Nullable String attribution,
            @Nullable String dedupKey,
            @Nullable String payload,
            @Nullable String context,
            @Nullable String provenance) {

        JsonObject provenanceJson() {
            return jsonObject(provenance);
        }
    }

    /**
     * A {@code correlated} row of {@code workflow_dedup}.
     *
     * @param namespace the hashed namespace
     * @param key the hashed dedup key
     */
    record DedupRow(String namespace, String key) {}

    /**
     * An entry of an instance's history.
     *
     * @param type the entry type
     * @param payload the entry's payload as JSON text
     */
    record HistoryRow(String type, String payload) {}

    /**
     * An instance row.
     *
     * @param status the instance status
     * @param step the current step
     * @param scope the scope the instance started in
     * @param state the state as JSON
     */
    record InstanceRow(String status, String step, String scope, JsonObject state) {}

    // --- feeds ---

    /** One source of the scenario: a topic, a group, a consumer name and the definition of the source. */
    final class Feed<T> {

        private final String name;
        private final String topic;
        private final String group;
        private final Class<T> type;
        private final Function<KafkaSignals<T>, KafkaConsumerBinding<T>> definition;
        private int partitions = 1;
        private int maxRetries = 2;
        private long backoffMs = 50;
        private long maxBackoffMs = 60_000;

        private Feed(
                String name,
                String topic,
                String group,
                Class<T> type,
                Function<KafkaSignals<T>, KafkaConsumerBinding<T>> definition) {
            this.name = name;
            this.topic = topic;
            this.group = group;
            this.type = type;
            this.definition = definition;
        }

        /** The source's name, which is also its consumer's name. */
        String name() {
            return name;
        }

        /** The topic the source reads. */
        String topic() {
            return topic;
        }

        /** The consumer group of the source. */
        String group() {
            return group;
        }

        /** The dead-letter topic of the source. */
        String deadLetterTopic() {
            return topic + ".dlq";
        }

        /** Gives the topic the number of partitions. */
        Feed<T> partitions(int partitions) {
            this.partitions = partitions;
            return this;
        }

        /** Sets the retry budget of the consumer: how often a record is retried, and the wait between two attempts. */
        Feed<T> retry(int maxRetries, long backoffMs) {
            this.maxRetries = maxRetries;
            this.backoffMs = backoffMs;
            this.maxBackoffMs = Math.max(backoffMs, 60_000);
            return this;
        }

        /** Sets a retry budget whose wait stays at {@code backoffMs} however often the record is retried. */
        Feed<T> steadyRetry(int maxRetries, long backoffMs) {
            this.maxRetries = maxRetries;
            this.backoffMs = backoffMs;
            this.maxBackoffMs = backoffMs;
            return this;
        }

        /** Produces the message as JSON with no key and no header. */
        Produced produce(T message) {
            return produce(null, message, Map.of(), null);
        }

        /** Produces the message as JSON under the Kafka key, with the headers. */
        Produced produce(@Nullable String key, T message, Map<String, String> headers) {
            return produce(key, message, headers, null);
        }

        /** Produces the message as JSON with the headers. */
        Produced produce(T message, Map<String, String> headers) {
            return produce(null, message, headers, null);
        }

        /** Produces the message as JSON on the partition, under the Kafka key, with the headers. */
        Produced produce(@Nullable String key, T message, Map<String, String> headers, @Nullable Integer partition) {
            try {
                return send(topic, partition, key, DatabindCodec.mapper().writeValueAsBytes(message), headers);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("the test message does not encode", e);
            }
        }

        /** Produces bytes as they are. */
        Produced produceRaw(@Nullable String key, byte[] value, Map<String, String> headers) {
            return send(topic, null, key, value, headers);
        }

        /** Produces a record with no value. */
        Produced produceNull(@Nullable String key) {
            return send(topic, null, key, null, Map.of());
        }

        /** Reads the dead-letter topic of the source. */
        DlqReader dlq() {
            return DlqReader.of(Infrastructure.bootstrap(), deadLetterTopic());
        }

        /** Waits until the group has committed past the record. */
        boolean committed(Produced record) {
            long deadline = System.nanoTime() + AWAIT.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    Map<TopicPartition, OffsetAndMetadata> offsets = admin().listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
                    OffsetAndMetadata at = offsets.get(new TopicPartition(record.topic(), record.partition()));
                    if (at != null && at.offset() > record.offset()) {
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException | TimeoutException e) {
                    // the coordinator may not answer yet; ask again
                }
                pause(100);
            }
            return false;
        }

        /**
         * Stops the consumers and starts new ones in the same group, without touching the group's committed offset, as
         * after a process restart: a record the group has not committed is consumed again.
         */
        void restartConsumers() {
            await(node.consumerDeployments().undeployAll());
            await(node.consumerDeployments().deployAll());
            awaitAssigned(this);
        }

        /**
         * Resets the group's committed offset to the record, through the admin client, and restarts the consumers, so
         * that the record is consumed a second time at the same topic, partition and offset.
         */
        void rewindTo(Produced record) {
            await(node.consumerDeployments().undeployAll());
            long deadline = System.nanoTime() + AWAIT.toNanos();
            while (true) {
                try {
                    admin().alterConsumerGroupOffsets(
                                    group,
                                    Map.of(
                                            new TopicPartition(record.topic(), record.partition()),
                                            new OffsetAndMetadata(record.offset())))
                            .all()
                            .get(10, TimeUnit.SECONDS);
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while rewinding", e);
                } catch (ExecutionException | TimeoutException e) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("the group's offset could not be reset", e);
                    }
                    pause(200);
                }
            }
            await(node.consumerDeployments().deployAll());
            awaitAssigned(this);
        }
    }

    // --- state of one scenario ---

    private final Vertx vertx;
    private final String label;
    private final String id;
    private final List<Feed<?>> feeds = new ArrayList<>();
    private final List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources = new ArrayList<>();
    private final RecordingObserver observer = new RecordingObserver();
    private final RecordingInterceptor interceptor = new RecordingInterceptor();
    private final List<Waiting> instances = new CopyOnWriteArrayList<>();
    private final AtomicBoolean losingAcknowledgement = new AtomicBoolean();
    private final AtomicInteger starts = new AtomicInteger();
    private final List<AutoCloseable> cleanups = new ArrayList<>();
    private boolean installLosingPool;
    private int maxOpenSignals = 100;
    private int maxPayloadBytes = 262_144;
    private int logMark;
    private Pool nodePool;
    private Pool readPool;
    private SourceNode node;
    private AdminClient admin;
    private KafkaProducer<String, byte[]> producer;

    private SourceScenario(Vertx vertx, String label) {
        this.vertx = vertx;
        this.label = label;
        this.id = Long.toString(System.currentTimeMillis() % 100_000_000L + SEQUENCE.incrementAndGet(), 36);
    }

    /**
     * Opens a scenario that has no source yet.
     *
     * @param vertx the Vert.x instance the pools and the node run on
     * @param label a short word for the proof, which names its topics and groups
     * @return the scenario
     */
    static SourceScenario open(Vertx vertx, String label) {
        SourceScenario scenario = new SourceScenario(vertx, label);
        scenario.logMark = RecordingLoggerProvider.mark();
        return scenario;
    }

    /** A value that is unique to this scenario and starts with the base, such as a carrier reference. */
    String ref(String base) {
        return base + "-" + id;
    }

    /** A message of this scenario that was delivered, with an event id of its own. */
    ShipmentDelivered delivered(String eventSuffix, String carrierRef) {
        return new ShipmentDelivered(
                "evt-" + eventSuffix + "-" + id,
                "delivered",
                carrierRef,
                ShipmentDelivered.CARRIER,
                ShipmentDelivered.WEIGHT,
                ShipmentDelivered.DELIVERED_AT,
                "left at the door");
    }

    /** Maps a message to the payload the signals take: only when it was delivered. */
    static DeliveryConfirmed confirmation(ShipmentDelivered message) {
        return DeliveryConfirmed.at(message.deliveredAt());
    }

    /** Declares a source over a message type. */
    <T> Feed<T> feed(String name, Class<T> type, Function<KafkaSignals<T>, KafkaConsumerBinding<T>> definition) {
        String base = label + "-" + id + "." + name;
        Feed<T> feed = new Feed<>(name, base, "g-" + base, type, definition);
        feeds.add(feed);
        sources.add(signals -> feed.definition.apply(
                signals.on(feed.topic, feed.type).groupId(feed.group).name(feed.name)));
        return feed;
    }

    /** Declares a source over the carriers' message type. */
    Feed<ShipmentDelivered> carriers(
            String name,
            Function<KafkaSignals<ShipmentDelivered>, KafkaConsumerBinding<ShipmentDelivered>> definition) {
        return feed(name, ShipmentDelivered.class, definition);
    }

    /** Bounds the open signals of an instance and the payload of a signal. */
    SourceScenario limits(int maxOpenSignalsPerInstance, int maxPayloadBytes) {
        this.maxOpenSignals = maxOpenSignalsPerInstance;
        this.maxPayloadBytes = maxPayloadBytes;
        return this;
    }

    /** Installs a pool decorator, so that {@link #loseOneAcknowledgement()} can model a crash after commit. */
    SourceScenario losingAcknowledgements() {
        this.installLosingPool = true;
        return this;
    }

    /**
     * Arms the pool decorator: the next transaction the delivery bean commits completes, and then its future fails once,
     * as if the process had crashed after the commit and before the acknowledgement.
     */
    void loseOneAcknowledgement() {
        if (!installLosingPool) {
            throw new IllegalStateException("open the scenario with losingAcknowledgements()");
        }
        losingAcknowledgement.set(true);
    }

    /** The events the delivery bean reported to its observers. */
    RecordingObserver observer() {
        return observer;
    }

    /** The completions the consumers reported for each record. */
    RecordingInterceptor completions() {
        return interceptor;
    }

    /** The log records the framework wrote since the scenario opened. */
    List<String> logs() {
        return RecordingLoggerProvider.since(logMark);
    }

    /** The node's holder of the ambient context. */
    dev.vertique.core.context.ContextHolder contextHolder() {
        return node.contextHolder();
    }

    /** The scope the ambient context holds, or empty. */
    Optional<String> ambientScope() {
        return node.contextHolder().current(ScopeContext.class).map(scope -> scope.scopeKey()
                .value());
    }

    // --- start and stop ---

    /** Starts the node: the topics, the pools, the graph, the executor and the consumers, and waits for assignments. */
    SourceScenario start() {
        Infrastructure.database();
        createTopics();
        nodePool = openPool(4 + 16);
        readPool = openPool(4);
        Pool decorated = installLosingPool ? losing(nodePool) : nodePool;
        JsonObject consumers = new JsonObject();
        for (Feed<?> feed : feeds) {
            consumers.put(
                    feed.name,
                    new JsonObject()
                            .put("deadLetterTopic", feed.deadLetterTopic())
                            .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                            .put(
                                    "retry",
                                    new JsonObject()
                                            .put("maxRetries", feed.maxRetries)
                                            .put("backoffMs", feed.backoffMs)
                                            .put("backoffMultiplier", 2.0)
                                            .put("maxBackoffMs", feed.maxBackoffMs)
                                            .put("exhaustedStrategy", "DEAD_LETTER")));
        }
        JsonObject config = new JsonObject()
                .put(
                        "kafka",
                        new JsonObject()
                                .put("bootstrap.servers", Infrastructure.bootstrap())
                                .put("consumers", consumers))
                .put(
                        "workflow",
                        new JsonObject()
                                .put("executor", new JsonObject().put("pollIntervalMs", 100))
                                .put(
                                        "recording",
                                        new JsonObject()
                                                .put("maxOpenSignalsPerInstance", maxOpenSignals)
                                                .put("maxPayloadBytes", maxPayloadBytes)));
        node = DaggerSourceNode.builder()
                .vertxModule(new VertxModule(vertx, config))
                .rig(new SourceNode.Rig(decorated, observer, interceptor, sources))
                .build();
        runStartupSteps();
        resumeExecutor();
        await(node.consumerDeployments().deployAll());
        for (Feed<?> feed : feeds) {
            awaitAssigned(feed);
        }
        return this;
    }

    private void runStartupSteps() {
        List<ApplicationStartupStep> steps = node.startupSteps().stream()
                .filter(step -> step.phase().ordinal() < LifecyclePhase.BOOTSTRAP.ordinal()
                        || step.getClass().getSimpleName().equals("WorkflowDefinitionRegistryBootstrapStep"))
                .sorted(LifecycleOrdered.comparator())
                .toList();
        for (ApplicationStartupStep step : steps) {
            await(step.start());
        }
    }

    /** Stops the workflow executor, so that recorded commands stay open. */
    void pauseExecutor() {
        await(node.verticleDeployer().undeploy(EXECUTOR));
    }

    /** Starts the workflow executor. */
    void resumeExecutor() {
        VerticleDeployment executor = node.verticleDeployments().stream()
                .filter(deployment -> EXECUTOR.equals(deployment.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the node contributes no " + EXECUTOR));
        await(node.verticleDeployer().deploy(executor));
    }

    @Override
    public void close() {
        List<Throwable> failures = new ArrayList<>();
        attempt(failures, () -> {
            if (node != null) {
                await(node.consumerDeployments().undeployAll());
            }
        });
        attempt(failures, () -> {
            if (node != null) {
                await(node.verticleDeployer().undeployAll());
            }
        });
        attempt(failures, () -> {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        });
        attempt(failures, () -> {
            if (admin != null) {
                try {
                    admin.deleteConsumerGroups(
                                    feeds.stream().map(feed -> feed.group).toList())
                            .all()
                            .get(10, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException e) {
                    // a group that never formed has nothing to delete
                }
                admin.close(Duration.ofSeconds(5));
            }
        });
        for (AutoCloseable cleanup : cleanups) {
            attempt(failures, cleanup::close);
        }
        attempt(failures, () -> {
            if (nodePool != null) {
                await(nodePool.close());
            }
        });
        attempt(failures, () -> {
            if (readPool != null) {
                await(readPool.close());
            }
        });
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("the scenario did not close cleanly", failures.get(0));
            failures.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }
    }

    /** Registers something the scenario closes with it, such as a released promise. */
    void onClose(AutoCloseable cleanup) {
        cleanups.add(cleanup);
    }

    private static void attempt(List<Throwable> failures, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // --- instances ---

    /** Starts an order in the scope that waits for a delivery under the reference, and waits until it waits. */
    Waiting waitingOrder(String scope, String carrierRef) {
        return waitingOrder(scope, carrierRef, null);
    }

    /** Starts an order in the scope, with a business key, that waits for a delivery under the reference. */
    Waiting waitingOrder(String scope, String carrierRef, @Nullable String businessKey) {
        return waiting(OrderShipping.ID, "orders", scope, carrierRef, businessKey);
    }

    /** Starts a return in the scope that waits for a delivery under the reference. */
    Waiting waitingReturn(String scope, String carrierRef) {
        return waiting(ReturnShipping.ID, "returns", scope, carrierRef, null);
    }

    private Waiting waiting(
            String definitionId, String contract, String scope, String carrierRef, @Nullable String businessKey) {
        WorkflowInstanceId started = await(node.workflowOperations()
                .start(new StartCommand(
                        definitionId,
                        contract,
                        new StartShipping(carrierRef),
                        "start-" + id + "-" + starts.incrementAndGet(),
                        businessKey,
                        null,
                        null,
                        scope,
                        null)));
        await(node.executionProbe().awaitIdle(started, IDLE));
        Waiting waiting = new Waiting(started, scope, carrierRef);
        instances.add(waiting);
        return waiting;
    }

    /** Waits until the engine has run the work recorded for each instance. */
    void awaitIdle(Waiting... waiting) {
        for (Waiting one : waiting) {
            await(node.executionProbe().awaitIdle(one.id(), IDLE));
        }
    }

    /**
     * Waits until the record has settled and the engine has run the work recorded for each instance.
     *
     * @return the settling completion of the record
     */
    RecordingInterceptor.Completion awaitDelivered(Produced record, Waiting... waiting) {
        RecordingInterceptor.Completion settled = awaitSettled(record, AWAIT);
        awaitIdle(waiting);
        return settled;
    }

    /** Waits for the record to settle, failing when it does not in time. */
    RecordingInterceptor.Completion awaitSettled(Produced record, Duration timeout) {
        return interceptor
                .awaitSettled(record, timeout)
                .orElseThrow(() -> new AssertionError("the record " + record + " did not settle within " + timeout
                        + "; completions: " + interceptor.outcomesOf(record)));
    }

    /** Sets the number of open signals the queue of the instance counts, as if that many were recorded and not yet run. */
    void fillOpenSignals(Waiting waiting, int open) {
        await(readPool.preparedQuery("UPDATE workflow_command_queues SET open_signals = $2 WHERE workflow_id = $1")
                .execute(Tuple.of(waiting.uuid(), open)));
    }

    /** Settles every open signal of the instance, so that the limit no longer holds. */
    void liftOpenSignals(Waiting waiting) {
        fillOpenSignals(waiting, 0);
    }

    // --- reads ---

    /** The {@code SIGNAL} commands of the instance, oldest first. */
    List<CommandRow> commands(Waiting waiting) {
        List<CommandRow> rows = new ArrayList<>();
        for (Row row : await(readPool.preparedQuery(
                        "SELECT id, state, actor_kind, actor_id, actor_reason, attribution_status, dedup_key,"
                                + " payload_json::text AS payload, context_json::text AS context,"
                                + " provenance_json::text AS provenance FROM workflow_commands"
                                + " WHERE workflow_id = $1 AND kind = 'SIGNAL' ORDER BY id")
                .execute(Tuple.of(waiting.uuid())))) {
            rows.add(new CommandRow(
                    row.getLong("id"),
                    row.getString("state"),
                    row.getString("actor_kind"),
                    row.getString("actor_id"),
                    row.getString("actor_reason"),
                    row.getString("attribution_status"),
                    row.getString("dedup_key"),
                    row.getString("payload"),
                    row.getString("context"),
                    row.getString("provenance")));
        }
        return rows;
    }

    /** The one {@code SIGNAL} command of the instance; fails when the instance has none or more than one. */
    CommandRow onlyCommand(Waiting waiting) {
        List<CommandRow> rows = commands(waiting);
        if (rows.size() != 1) {
            throw new AssertionError("expected exactly one SIGNAL command on the instance but found " + rows.size());
        }
        return rows.get(0);
    }

    /** The number of {@code SIGNAL} commands across every instance this scenario started. */
    long commandCount() {
        long total = 0;
        for (Waiting waiting : instances) {
            total += commands(waiting).size();
        }
        return total;
    }

    /** The {@code correlated} dedup rows of the instance. */
    List<DedupRow> dedupRows(Waiting waiting) {
        List<DedupRow> rows = new ArrayList<>();
        for (Row row : await(readPool.preparedQuery("SELECT dedup_namespace, key FROM workflow_dedup"
                        + " WHERE workflow_id = $1 AND kind = 'correlated' ORDER BY created_at")
                .execute(Tuple.of(waiting.uuid())))) {
            rows.add(new DedupRow(row.getString("dedup_namespace"), row.getString("key")));
        }
        return rows;
    }

    /** The history of the instance, oldest first. */
    List<HistoryRow> history(Waiting waiting) {
        List<HistoryRow> rows = new ArrayList<>();
        for (Row row :
                await(readPool.preparedQuery("SELECT entry_type, payload_json::text AS payload FROM workflow_history"
                                + " WHERE workflow_id = $1 ORDER BY sequence")
                        .execute(Tuple.of(waiting.uuid())))) {
            rows.add(new HistoryRow(row.getString("entry_type"), row.getString("payload")));
        }
        return rows;
    }

    /** The number of {@code SIGNAL_RECEIVED} entries in the history of the instance. */
    long signalsApplied(Waiting waiting) {
        return history(waiting).stream()
                .filter(entry -> entry.type().equals("SIGNAL_RECEIVED"))
                .count();
    }

    /** The row of the instance. */
    InstanceRow instance(Waiting waiting) {
        Row row = await(readPool.preparedQuery("SELECT status, current_step_id, scope_key, state_json::text AS state"
                                + " FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(waiting.uuid())))
                .iterator()
                .next();
        return new InstanceRow(
                row.getString("status"),
                row.getString("current_step_id"),
                row.getString("scope_key"),
                jsonObject(row.getString("state")));
    }

    // --- kafka plumbing ---

    private Produced send(
            String topic,
            @Nullable Integer partition,
            @Nullable String key,
            @Nullable byte[] value,
            Map<String, String> headers) {
        List<org.apache.kafka.common.header.Header> kafkaHeaders = new ArrayList<>();
        headers.forEach(
                (name, text) -> kafkaHeaders.add(new RecordHeader(name, text.getBytes(StandardCharsets.UTF_8))));
        try {
            RecordMetadata metadata = producer()
                    .send(new ProducerRecord<>(topic, partition, key, value, kafkaHeaders))
                    .get(20, TimeUnit.SECONDS);
            return new Produced(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while producing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("the record was not produced", e);
        }
    }

    private synchronized KafkaProducer<String, byte[]> producer() {
        if (producer == null) {
            producer = new KafkaProducer<>(
                    Map.of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.bootstrap(),
                            ProducerConfig.ACKS_CONFIG, "all",
                            ProducerConfig.LINGER_MS_CONFIG, "0"),
                    new StringSerializer(),
                    new ByteArraySerializer());
        }
        return producer;
    }

    private synchronized AdminClient admin() {
        if (admin == null) {
            admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, Infrastructure.bootstrap(),
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000",
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000"));
        }
        return admin;
    }

    private void createTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (Feed<?> feed : feeds) {
            topics.add(new NewTopic(feed.topic, feed.partitions, (short) 1));
            topics.add(new NewTopic(feed.deadLetterTopic(), 1, (short) 1));
        }
        try {
            admin().createTopics(topics).all().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while creating topics", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("the topics were not created", e);
        }
    }

    private void awaitAssigned(Feed<?> feed) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ConsumerGroupDescription group = admin().describeConsumerGroups(List.of(feed.group))
                        .describedGroups()
                        .get(feed.group)
                        .get(10, TimeUnit.SECONDS);
                int assigned = group.members().stream()
                        .mapToInt(
                                member -> member.assignment().topicPartitions().size())
                        .sum();
                if (assigned >= feed.partitions) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the assignment", e);
            } catch (ExecutionException | TimeoutException e) {
                // the group may not exist yet; ask again
            }
            pause(100);
        }
        throw new AssertionError("the consumers of " + feed.group + " were not assigned their partitions");
    }

    // --- database plumbing ---

    private Pool openPool(int size) {
        DbPoolConfig config = Infrastructure.database().toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(size))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    /**
     * Wraps the pool so that, once armed, the first transaction the delivery bean runs commits and then fails its
     * future. Only a call that has the delivery bean on its stack is touched; the executor's transactions pass.
     */
    private Pool losing(Pool real) {
        return (Pool) Proxy.newProxyInstance(
                Pool.class.getClassLoader(), new Class<?>[] {Pool.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("withTransaction")
                            && result instanceof Future<?> future
                            && calledByDelivery()
                            && losingAcknowledgement.compareAndSet(true, false)) {
                        return future.<Object>compose(value -> Future.failedFuture(
                                new IllegalStateException("the acknowledgement was lost after the commit")));
                    }
                    return result;
                });
    }

    private static boolean calledByDelivery() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.anyMatch(frame -> frame.getDeclaringClass()
                        .getName()
                        .equals("dev.vertique.workflow.services.source.WorkflowSourceDelivery")));
    }

    // --- shared infrastructure ---

    /** The broker and the database every scenario of the JVM shares. */
    static final class Infrastructure {

        private static PostgresContainer database;

        private Infrastructure() {}

        static String bootstrap() {
            return KafkaTestContainers.shared().getBootstrapServers();
        }

        static synchronized PostgresContainer database() {
            if (database == null) {
                PostgresContainer started = new PostgresContainer()
                        .withDatabaseName("workflow_kafka_signals")
                        .withMigration("classpath:db/migration/workflow");
                started.start();
                database = started;
            }
            return database;
        }
    }

    // --- awaiting futures ---

    /**
     * Reads the text of a {@code jsonb} value as an object, whether the column holds the object itself or a JSON string
     * that holds the object's text.
     */
    static JsonObject jsonObject(String text) {
        Object value = new io.vertx.core.json.JsonArray("[" + text + "]").getValue(0);
        if (value instanceof JsonObject object) {
            return object;
        }
        if (value instanceof String inner) {
            return new JsonObject(inner);
        }
        throw new AssertionError("the column does not hold an object: " + value);
    }

    /** Waits for the future on the calling thread. */
    static <V> V await(Future<V> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("a future did not complete within " + AWAIT, e);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- builders of the headers and identities the proofs send ---

    /** Headers of a record. */
    static final class Headers {

        /** The header that carries the correlation of a durable context. */
        static final String CORRELATION = "vertique-correlation";

        /** The header that carries the identity snapshot of a durable context. */
        static final String IDENTITY_SNAPSHOT = "vertique-identity-snapshot";

        private Headers() {}

        /** A correlation document as the framework's own encoder writes it. */
        static String correlation(String correlationId, String requestId) {
            return new JsonObject()
                    .put("schemaVersion", 1)
                    .put("requestId", new JsonObject().put("value", requestId).put("source", "test"))
                    .put(
                            "correlationId",
                            new JsonObject().put("value", correlationId).put("source", "test"))
                    .encode();
        }

        /** An identity snapshot document that names the user. */
        static String identitySnapshot(String user) {
            String snapshot = new JsonObject()
                    .put("schemaVersion", 1)
                    .put(
                            "content",
                            new JsonObject()
                                    .put(
                                            "actor",
                                            new JsonObject().put("type", "USER").put("id", user)))
                    .encode();
            return new JsonObject()
                    .put("snapshot", Base64.getEncoder().encodeToString(snapshot.getBytes(StandardCharsets.UTF_8)))
                    .put("present", true)
                    .encode();
        }

        /** An identity snapshot document that cannot be decoded. */
        static String malformedIdentitySnapshot() {
            return new JsonObject()
                    .put("snapshot", "!!not-base64!!")
                    .put("present", true)
                    .encode();
        }

        /** The headers of a durable context: the correlation and the identity snapshot, either of them or none. */
        static Map<String, String> durable(@Nullable String correlation, @Nullable String identitySnapshot) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (correlation != null) {
                headers.put(CORRELATION, correlation);
            }
            if (identitySnapshot != null) {
                headers.put(IDENTITY_SNAPSHOT, identitySnapshot);
            }
            return headers;
        }

        /** A correlation header of the given length, which no document fits. */
        static Map<String, String> oversized(int length) {
            return Map.of(CORRELATION, "x".repeat(length));
        }

        /** A correlation header with the text as its value. */
        static Map<String, String> rawCorrelation(String text) {
            return Map.of(CORRELATION, text);
        }

        /** The headers that name the position a dead-lettered record was consumed from. */
        static Map<String, String> dlqRecord(String topic, int partition, long offset) {
            return dlqRecord(topic, partition, Long.toString(offset));
        }

        /** The headers that name the position a dead-lettered record was consumed from, with the offset as text. */
        static Map<String, String> dlqRecord(String topic, int partition, String offset) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(KafkaDlqHeaders.SOURCE_TOPIC, topic);
            headers.put(KafkaDlqHeaders.SOURCE_PARTITION, Integer.toString(partition));
            headers.put(KafkaDlqHeaders.SOURCE_OFFSET, offset);
            return headers;
        }

        /** One header. */
        static Map<String, String> of(String name, String value) {
            return Map.of(name, value);
        }

        /** Both maps' headers. */
        static Map<String, String> and(Map<String, String> first, Map<String, String> second) {
            Map<String, String> headers = new LinkedHashMap<>(first);
            headers.putAll(second);
            return headers;
        }
    }

    /** Identities a source answers. */
    static final class Identities {

        private Identities() {}

        static SecurityIdentity user(String id) {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
        }

        static SecurityIdentity anonymous() {
            return SecurityIdentity.anonymous();
        }

        /**
         * A system identity whose reason is blank: the framework's actor mapper refuses to turn it into an actor, and
         * the engine refuses the recording with it.
         */
        static SecurityIdentity refusedByMapper() {
            return new SecurityIdentity(
                    new PrincipalRef(PrincipalType.SYSTEM, "system:refused", Map.of("system.reason", " ")),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        /** An answer that fails with the marker a source uses to refuse an identity. */
        static Future<Optional<SecurityIdentity>> refusing(String errorType) {
            return Future.failedFuture(new SignalSourceRefusal(errorType));
        }

        /** An answer that names the identity. */
        static Future<Optional<SecurityIdentity>> answer(SecurityIdentity identity) {
            return Future.succeededFuture(Optional.of(identity));
        }

        /** An answer that names no identity. */
        static Future<Optional<SecurityIdentity>> none() {
            return Future.succeededFuture(Optional.empty());
        }
    }

    /** Scripts the answers of a lookup, call by call. */
    static final class LookupScript {

        private final List<String> answers;
        private final String failure;
        private final AtomicInteger calls = new AtomicInteger();
        private final Promise<Void> gate = Promise.promise();
        private final boolean gated;

        private LookupScript(List<String> answers, @Nullable String failure, boolean gated) {
            this.answers = answers;
            this.failure = failure;
            this.gated = gated;
            if (!gated) {
                gate.complete();
            }
        }

        /** Answers the values in turn; the last one answers every later call. */
        static LookupScript answers(String... values) {
            return new LookupScript(List.of(values), null, false);
        }

        /** Fails the first call with the message, then answers the value. */
        static LookupScript failsOnce(String message, String then) {
            return new LookupScript(List.of(then), message, false);
        }

        /** Fails the first call with the message; every later call answers the value only after {@link #release()}. */
        static LookupScript failsOnceThenHolds(String message, String then) {
            return new LookupScript(List.of(then), message, true);
        }

        /** Lets the held calls answer. */
        void release() {
            gate.tryComplete();
        }

        /** How often the lookup was called. */
        int calls() {
            return calls.get();
        }

        /** The lookup, as a source stage takes it. */
        Function<SignalMessage<ShipmentDelivered>, Future<Optional<String>>> lookup() {
            return message -> {
                int call = calls.incrementAndGet();
                if (failure != null && call == 1) {
                    return Future.failedFuture(new IllegalStateException(failure));
                }
                int index = failure != null ? call - 2 : call - 1;
                String answer = answers.get(Math.min(Math.max(index, 0), answers.size() - 1));
                return gate.future().map(Optional.of(answer));
            };
        }
    }

    /** A {@code dispatchAs} that answers late or never. */
    static final class Stalled {

        private final AtomicInteger calls = new AtomicInteger();
        private final Promise<Optional<SecurityIdentity>> stalled = Promise.promise();

        private Stalled() {}

        /** Never answers the first call; answers every later call with no identity. */
        static Stalled once() {
            return new Stalled();
        }

        /** The stage, as a source takes it. */
        Function<SignalMessage<ShipmentDelivered>, Future<Optional<SecurityIdentity>>> dispatchAs() {
            return message -> calls.incrementAndGet() == 1 ? stalled.future() : Identities.none();
        }

        /** Fails the promise that never settled. */
        void fail() {
            stalled.tryFail("released by the test");
        }
    }

    /**
     * Holds the answer of each {@code dispatchAs} call until the test releases it, and completes it from one Vert.x
     * context that is not the consumer's. A source handles one record at a time, so the second call opens only once the
     * first record is settled.
     */
    final class Overlap {

        private final Context foreign = vertx.getOrCreateContext();
        private final List<String> users = Collections.synchronizedList(new ArrayList<>());
        private final List<Promise<Optional<SecurityIdentity>>> promises =
                Collections.synchronizedList(new ArrayList<>());

        Overlap() {
            cleanups.add(this::releaseAll);
        }

        /** The stage, as a source takes it: it answers the user of the message's {@code x-user} header. */
        Function<SignalMessage<ShipmentDelivered>, Future<Optional<SecurityIdentity>>> dispatchAs() {
            return message -> {
                Promise<Optional<SecurityIdentity>> promise = Promise.promise();
                users.add(message.header("x-user").orElse("?"));
                promises.add(promise);
                return promise.future();
            };
        }

        /** Completes the two calls in the order they open, each as soon as it has opened. */
        void release() {
            for (int index = 0; index < 2; index++) {
                long deadline = System.nanoTime() + IDLE.toNanos();
                while (promises.size() <= index && System.nanoTime() < deadline) {
                    pause(25);
                }
                if (promises.size() <= index) {
                    throw new AssertionError("call " + index + " did not open; open: " + promises.size());
                }
                Promise<Optional<SecurityIdentity>> promise = promises.get(index);
                String user = users.get(index);
                foreign.runOnContext(ignored -> promise.tryComplete(Optional.of(Identities.user(user))));
            }
        }

        private void releaseAll() {
            promises.forEach(promise -> promise.tryComplete(Optional.empty()));
        }
    }

    /** A text of the given length. */
    static String ofSize(int length) {
        return "n".repeat(length);
    }

    /** The outcomes a record that was neither retried nor dead-lettered can end with. */
    static final Set<KafkaTerminalOutcome> COMMITTED_WITHOUT_RETRY =
            Set.of(KafkaTerminalOutcome.SUCCESS, KafkaTerminalOutcome.SKIP);
}
