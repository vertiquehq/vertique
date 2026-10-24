// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.IsolationLevel;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.PgWorkflowEngineTestSupport;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.LoggerFactory;

/**
 * Engines with controllable executors over one database, the fixture workflows they run, and the observation of what
 * recording and executing leave behind.
 *
 * <p>Every engine is built with its executor stopped; a test starts and stops each executor itself. The fixture
 * workflows dispatch service calls to a test recorder that writes a row into a test-owned table through the
 * transaction it is handed, so the table shows exactly what a segment's transaction committed. The same recorder also
 * keeps an in-memory log of every call it received, which shows what ran even when the transaction rolled back.
 */
final class ExecutorFixture implements AutoCloseable {

    /** How long a test waits for a future that must complete. */
    static final Duration AWAIT = Duration.ofSeconds(20);

    /** How long a test waits for recorded work to run. */
    static final Duration IDLE = Duration.ofSeconds(15);

    static final String SKELETON = "recorded-skeleton";
    static final String LATE = "recorded-late";
    static final String ATOMIC = "recorded-atomic";
    static final String LOOP = "recorded-loop";

    private static final String CORRELATION_NAMESPACE = "correlation";
    private static final String IDENTITY_NAMESPACE = "identity-snapshot";

    // --- Fixture types ---

    /**
     * Start payload of every fixture workflow.
     *
     * @param ref the label of the instance
     */
    record FlowStart(String ref) {}

    /**
     * State of every fixture workflow.
     *
     * @param ref the label of the instance
     */
    record FlowState(String ref) {}

    /** Contract of the skeleton workflow. */
    interface SkeletonContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the workflow that writes before and after a long run of decisions. */
    interface LateContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the workflow with two dispatches. */
    interface AtomicContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the workflow whose only decision loops to itself. */
    interface LoopContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /**
     * A correlation identifier in the durable context.
     *
     * @param correlationId the identifier
     */
    record CorrelationCtx(String correlationId) implements ContextValue {}

    /**
     * Stands for the identity snapshot an authenticated caller carries in the durable context.
     *
     * @param subject the subject of record
     */
    record IdentitySnapshotCtx(String subject) implements ContextValue {}

    /** A row of {@code workflow_commands}, as the tests read it. */
    record CommandRow(
            long id,
            long seq,
            String kind,
            String state,
            int attempts,
            @Nullable UUID claimToken,
            @Nullable String claimedBy,
            @Nullable OffsetDateTime leaseUntil,
            @Nullable OffsetDateTime settledAt,
            @Nullable JsonObject payload,
            int transientCount,
            @Nullable String lastError,
            OffsetDateTime recordedAt,
            OffsetDateTime availableAt) {}

    /** A row of {@code workflow_instances}, as the tests read it. */
    record InstanceRow(
            String status,
            @Nullable String currentStepId,
            @Nullable Object stateJson,
            @Nullable String businessKey,
            @Nullable JsonObject metadata) {}

    // --- Shared infrastructure ---

    private final DbPoolConfig db;
    private final Vertx vertx;
    private final Pool observer;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Pool> extraPools = new CopyOnWriteArrayList<>();
    private final DefaultContextHolder holder = new DefaultContextHolder();
    private final DurableContextPropagator propagator;
    private final Logger workflowLogger = (Logger) LoggerFactory.getLogger("dev.vertique.workflow");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    /** Every service call a recorder received, in arrival order, including those whose transaction rolled back. */
    final List<IntentCall> intentCalls = new CopyOnWriteArrayList<>();

    /** The workflow events the engines' segments emitted, in the order they were recorded. */
    final List<WorkflowEventIntent> workflowEvents = new CopyOnWriteArrayList<>();

    /** The reference of every initial state computed, in order, including those whose transaction rolled back. */
    final List<String> initialStates = new CopyOnWriteArrayList<>();

    /** Receives the reference of each initial state while it is computed, on the thread that computes it. */
    volatile Consumer<String> onInitialState = ref -> {};

    /** The gate in the recorders' route. */
    final SegmentGate gate = new SegmentGate();

    /** Counts the calls of recorders that fail after writing. */
    final AtomicInteger recorderFailures = new AtomicInteger();

    /** The reference prefix of the instances whose second dispatch fails after it wrote. */
    volatile String poisonPrefix = "poison";

    /**
     * One service call a recorder received.
     *
     * @param workflowId the instance
     * @param stepId     the dispatching step
     * @param ref        the label of the instance
     */
    record IntentCall(UUID workflowId, String stepId, String ref) {}

    private ExecutorFixture(DbPoolConfig db) {
        this.db = db;
        this.vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(4));
        this.observer = pool(vertx, 4);
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(TenantCtxCodec.encoder(), correlationEncoder(), identityEncoder()),
                Set.of(TenantCtxCodec.decoder(), correlationDecoder(), identityDecoder()));
        this.propagator = new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        logs.start();
        workflowLogger.addAppender(logs);
    }

    /**
     * Opens the fixture over the database: creates the test-owned intent table and empties every workflow table.
     *
     * @param db the database under test, already migrated
     * @return the fixture
     */
    static ExecutorFixture open(DbPoolConfig db) {
        ExecutorFixture fixture = new ExecutorFixture(db);
        fixture.reset();
        return fixture;
    }

    /** Empties the workflow tables and the intent table, and forgets everything the recorders saw. */
    void reset() {
        await(observer.query("CREATE TABLE IF NOT EXISTS executor_it_outbox (workflow_id UUID NOT NULL,"
                        + " step_id VARCHAR(255) NOT NULL)")
                .execute());
        await(observer.query("TRUNCATE workflow_commands, workflow_command_queues, workflow_history, workflow_dedup,"
                        + " workflow_timers, workflow_tasks, workflow_instances, workflow_definitions,"
                        + " executor_it_outbox CASCADE")
                .execute());
        intentCalls.clear();
        workflowEvents.clear();
        initialStates.clear();
        logs.list.clear();
        recorderFailures.set(0);
    }

    Vertx vertx() {
        return vertx;
    }

    /** A pool of its own on the fixture's Vert.x instance, closed with the fixture. */
    Pool newPool(int size) {
        Pool pool = pool(vertx, size);
        extraPools.add(pool);
        return pool;
    }

    Pool observer() {
        return observer;
    }

    DefaultContextHolder holder() {
        return holder;
    }

    DurableContextPropagator propagator() {
        return propagator;
    }

    @Override
    public void close() {
        for (Node node : List.copyOf(nodes)) {
            node.close();
        }
        workflowLogger.detachAppender(logs);
        logs.stop();
        extraPools.forEach(Pool::close);
        observer.close();
        awaitQuietly(vertx.close());
    }

    // --- Nodes ---

    /**
     * Describes an engine with its executor stopped.
     *
     * @param name the name of the node, for failure messages
     * @return the description
     */
    NodeSpec node(String name) {
        return new NodeSpec(name);
    }

    /** The description of one engine and its executor. */
    final class NodeSpec {
        private final String name;
        private int instances = 1;
        private int concurrency = 8;
        private long pollIntervalMs = 100;
        private int leaseSeconds = 60;
        private boolean ownVertx;
        private boolean recordDeadlines;
        private boolean holdFirstDeadlineCall;
        private boolean carryContext;
        private int poolSize = -1;
        private int maxAttempts = 5;
        private long transientBackoffBaseMs = 200;
        private long transientBackoffMaxMs = 30_000;
        private int maxStepsPerSegment = 1000;
        private long stuckAfterSeconds = 300;
        private Set<WorkflowExecutorObserver> observers = Set.of();
        private UnaryOperator<WorkflowTransactionRunner<SqlClient>> runnerDecorator = UnaryOperator.identity();
        private UnaryOperator<WorkflowCommandRepository<SqlClient>> commandsDecorator = UnaryOperator.identity();

        private NodeSpec(String name) {
            this.name = name;
        }

        NodeSpec instances(int instances) {
            this.instances = instances;
            return this;
        }

        NodeSpec concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        NodeSpec pollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        NodeSpec leaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
            return this;
        }

        /** The engine runs on a Vert.x instance of its own, closed with the node. */
        NodeSpec ownVertx() {
            this.ownVertx = true;
            return this;
        }

        /** The engine's transaction runner records every call of the deadline overload. */
        NodeSpec recordDeadlines() {
            this.recordDeadlines = true;
            return this;
        }

        /** The first call of the deadline overload waits until the test releases it. */
        NodeSpec holdFirstDeadlineCall() {
            this.recordDeadlines = true;
            this.holdFirstDeadlineCall = true;
            return this;
        }

        /** The engine captures and binds the durable context of the fixture's propagator. */
        NodeSpec carryContext() {
            this.carryContext = true;
            return this;
        }

        NodeSpec commands(UnaryOperator<WorkflowCommandRepository<SqlClient>> decorator) {
            this.commandsDecorator = decorator;
            return this;
        }

        /** The engine's transaction runner is wrapped by the decorator, inside the deadline recording. */
        NodeSpec runner(UnaryOperator<WorkflowTransactionRunner<SqlClient>> decorator) {
            this.runnerDecorator = decorator;
            return this;
        }

        /** The size of the engine's connection pool, in place of the generous default. */
        NodeSpec poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        /** The claims a command may spend before it is dead-lettered. */
        NodeSpec maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** The backoff of a transient retry: it doubles from the base and is capped at the maximum. */
        NodeSpec transientBackoffMs(long baseMs, long maxMs) {
            this.transientBackoffBaseMs = baseMs;
            this.transientBackoffMaxMs = maxMs;
            return this;
        }

        /** The nodes one segment may execute before it is failed. */
        NodeSpec maxStepsPerSegment(int maxStepsPerSegment) {
            this.maxStepsPerSegment = maxStepsPerSegment;
            return this;
        }

        /** The age after which unsettled work is reported stuck. */
        NodeSpec stuckAfterSeconds(long stuckAfterSeconds) {
            this.stuckAfterSeconds = stuckAfterSeconds;
            return this;
        }

        /** The observers the engine's executor reports to. */
        NodeSpec observers(WorkflowExecutorObserver... observers) {
            this.observers = Set.of(observers);
            return this;
        }

        /** The one place that builds the executor configuration of a node. */
        private WorkflowExecutorConfig executorConfig() {
            return new WorkflowExecutorConfig(
                    instances,
                    concurrency,
                    pollIntervalMs,
                    leaseSeconds,
                    maxAttempts,
                    transientBackoffBaseMs,
                    transientBackoffMaxMs,
                    maxStepsPerSegment,
                    stuckAfterSeconds);
        }

        Node build() {
            Vertx nodeVertx = ownVertx ? Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2)) : vertx;
            Pool pool = pool(nodeVertx, poolSize > 0 ? poolSize : Math.max(20, instances * concurrency + 4));
            DeadlineRecordingRunner[] runnerRef = new DeadlineRecordingRunner[1];
            DefaultWorkflowRegistry registry = newRegistry();
            PgWorkflowEngineTestSupport.ExecutorEngine engine = PgWorkflowEngineTestSupport.executorEngine(
                            pool, nodeVertx, registry)
                    .recorders(Set.of(new IntentRecorder(), new EventRecorder()))
                    .executorConfig(executorConfig())
                    .executorObservers(observers)
                    .commandsDecorator(commandsDecorator);
            if (carryContext) {
                engine.propagator(propagator);
            }
            if (recordDeadlines) {
                engine.runnerDecorator(delegate -> {
                    runnerRef[0] = new DeadlineRecordingRunner(runnerDecorator.apply(delegate), holdFirstDeadlineCall);
                    return runnerRef[0];
                });
            } else {
                engine.runnerDecorator(runnerDecorator);
            }
            WorkflowEngineHandle handle = engine.build();
            Node node = new Node(name, nodeVertx, ownVertx, pool, handle, runnerRef[0]);
            nodes.add(node);
            return node;
        }
    }

    /** An engine with its executor, its pool and, when it owns one, its Vert.x instance. */
    final class Node implements AutoCloseable {
        final String name;
        final Vertx vertx;
        final Pool pool;
        final WorkflowEngineHandle engine;
        final DeadlineRecordingRunner runner;
        private final boolean ownsVertx;

        private Node(
                String name,
                Vertx vertx,
                boolean ownsVertx,
                Pool pool,
                WorkflowEngineHandle engine,
                DeadlineRecordingRunner runner) {
            this.name = name;
            this.vertx = vertx;
            this.ownsVertx = ownsVertx;
            this.pool = pool;
            this.engine = engine;
            this.runner = runner;
        }

        /** Starts the executor and waits until it is deployed. */
        void startExecutor() {
            await(engine.executor().start());
        }

        /** Stops the executor and waits until its in-flight segments ended. */
        void stopExecutor() {
            await(engine.executor().stop());
        }

        /** Records a start in the caller's own transaction and commits it. */
        WorkflowInstanceId record(StartCommand command) {
            return await(pool.withTransaction(tx -> engine.start(command, tx)));
        }

        /** Waits until the instance has no command in flight and none an executor could claim. */
        void awaitIdle(WorkflowInstanceId id) {
            await(engine.executionProbe().awaitIdle(id, IDLE));
        }

        @Override
        public void close() {
            try {
                await(engine.executor().stop(), Duration.ofSeconds(30));
            } catch (RuntimeException | AssertionError ignored) {
                // the executor may never have been started, or its stop may have failed
            }
            pool.close();
            if (ownsVertx) {
                awaitQuietly(vertx.close());
            }
            nodes.remove(this);
        }
    }

    // --- Starting ---

    /** A start of the skeleton workflow. */
    static StartCommand startOf(String ref) {
        return startOf(SKELETON, SkeletonContract.class, ref);
    }

    /** A start of a fixture workflow, with the label as business key and idempotency key. */
    static StartCommand startOf(String definitionId, Class<?> contract, String ref) {
        return new StartCommand(
                definitionId, contract.getName(), new FlowStart(ref), "idem-" + ref, ref, null, null, null, null);
    }

    // --- Observation ---

    /** The commands of the instance in queue order. */
    List<CommandRow> commandsOf(WorkflowInstanceId id) {
        List<CommandRow> commands = new ArrayList<>();
        for (Row row : await(observer.preparedQuery(
                        "SELECT id, seq, kind, state, attempts, claim_token, claimed_by, lease_until, settled_at,"
                                + " payload_json, transient_count, last_error, recorded_at, available_at"
                                + " FROM workflow_commands WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            commands.add(new CommandRow(
                    row.getLong("id"),
                    row.getLong("seq"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getInteger("attempts"),
                    row.getUUID("claim_token"),
                    row.getString("claimed_by"),
                    row.getOffsetDateTime("lease_until"),
                    row.getOffsetDateTime("settled_at"),
                    (JsonObject) row.getValue("payload_json"),
                    row.getInteger("transient_count"),
                    row.getString("last_error"),
                    row.getOffsetDateTime("recorded_at"),
                    row.getOffsetDateTime("available_at")));
        }
        return commands;
    }

    /** The only command of the instance. */
    CommandRow commandOf(WorkflowInstanceId id) {
        List<CommandRow> commands = commandsOf(id);
        if (commands.size() != 1) {
            throw new AssertionError("expected one command of " + id + " but found " + commands);
        }
        return commands.get(0);
    }

    /** The instance row, or {@code null} when there is none. */
    @Nullable
    InstanceRow instanceOf(WorkflowInstanceId id) {
        var rows = await(observer.preparedQuery("SELECT status, current_step_id, state_json, business_key, metadata"
                        + " FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value())));
        if (rows.size() == 0) {
            return null;
        }
        Row row = rows.iterator().next();
        return new InstanceRow(
                row.getString("status"),
                row.getString("current_step_id"),
                row.getValue("state_json"),
                row.getString("business_key"),
                (JsonObject) row.getValue("metadata"));
    }

    /** The entry types of the instance's history, in sequence order. */
    List<String> historyOf(WorkflowInstanceId id) {
        List<String> types = new ArrayList<>();
        for (Row row : await(observer.preparedQuery(
                        "SELECT entry_type FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            types.add(row.getString("entry_type"));
        }
        return types;
    }

    /** The steps of the instance's committed service intents, in no particular order. */
    List<String> intentsOf(WorkflowInstanceId id) {
        List<String> steps = new ArrayList<>();
        for (Row row : await(observer.preparedQuery("SELECT step_id FROM executor_it_outbox WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())))) {
            steps.add(row.getString("step_id"));
        }
        return steps;
    }

    /** The number of rows of the table that belong to the instance. */
    long rowsOf(String table, WorkflowInstanceId id) {
        return await(observer.preparedQuery("SELECT count(*) FROM " + table + " WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
    }

    /** The durable context stored on the instance, or {@code null} when none is stored. */
    @Nullable
    DurableMetadata instanceCarrierOf(WorkflowInstanceId id) {
        InstanceRow row = instanceOf(id);
        if (row == null || row.metadata() == null) {
            return null;
        }
        return DurableMetadata.fromCarrier(row.metadata());
    }

    /** The backends that hold the instance's execution lock. */
    List<Integer> holdersOfX(WorkflowInstanceId id) {
        int[] key = ExecutionLock.keyOf(id);
        List<Integer> pids = new ArrayList<>();
        for (Row row : await(observer.preparedQuery("SELECT pid FROM pg_locks WHERE locktype = 'advisory'"
                        + " AND objsubid = 2 AND granted AND classid::bigint = $1 AND objid::bigint = $2")
                .execute(Tuple.of(Integer.toUnsignedLong(key[0]), Integer.toUnsignedLong(key[1]))))) {
            pids.add(row.getInteger("pid"));
        }
        return pids;
    }

    /**
     * Takes the instance's execution lock in a transaction of the test that stays open until released.
     *
     * @param id the instance
     * @return the held lock
     */
    HeldLock holdExecutionLock(WorkflowInstanceId id) {
        int[] key = ExecutionLock.keyOf(id);
        SqlConnection connection = await(observer.getConnection());
        Transaction transaction = await(connection.begin());
        await(connection
                .preparedQuery("SELECT pg_advisory_xact_lock($1::int4, $2::int4)")
                .execute(Tuple.of(key[0], key[1])));
        return new HeldLock(connection, transaction);
    }

    /**
     * Takes the instance's execution lock in a transaction of the test, without blocking the calling thread.
     *
     * @param id the instance
     * @return a future of the held lock
     */
    Future<HeldLock> holdExecutionLockAsync(WorkflowInstanceId id) {
        int[] key = ExecutionLock.keyOf(id);
        return observer.getConnection().compose(connection -> connection.begin().compose(transaction -> connection
                .preparedQuery("SELECT pg_advisory_xact_lock($1::int4, $2::int4)")
                .execute(Tuple.of(key[0], key[1]))
                .map(ignored -> new HeldLock(connection, transaction))));
    }

    /** Whether the lease of the instance's only command has ended by the database clock. */
    boolean leaseExpired(WorkflowInstanceId id) {
        return await(observer.preparedQuery("SELECT bool_and(lease_until IS NULL OR lease_until < clock_timestamp())"
                                + " FROM workflow_commands WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getBoolean(0);
    }

    /** The number of advisory locks that some backend waits for. */
    long ungrantedAdvisoryLocks() {
        return await(observer.query("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                        .execute())
                .iterator()
                .next()
                .getLong(0);
    }

    /** The database clock now. */
    OffsetDateTime databaseNow() {
        return await(observer.query("SELECT clock_timestamp()").execute())
                .iterator()
                .next()
                .getOffsetDateTime(0);
    }

    /**
     * Whether the code is recorded as the reason of the instance: on the instance row or in one of its history
     * entries.
     */
    boolean reasonRecorded(WorkflowInstanceId id, String code) {
        return await(observer.preparedQuery("SELECT position($2 IN COALESCE(i.error_type, '') || ' '"
                                + " || COALESCE(i.error_message, '')) > 0 OR EXISTS (SELECT 1 FROM workflow_history h"
                                + " WHERE h.workflow_id = i.id AND position($2 IN h.payload_json::text) > 0)"
                                + " FROM workflow_instances i WHERE i.id = $1")
                        .execute(Tuple.of(id.value(), code)))
                .iterator()
                .next()
                .getBoolean(0);
    }

    /** The history entries of the instance as one text per entry: its type and payload. */
    List<String> historyTextsOf(WorkflowInstanceId id) {
        List<String> texts = new ArrayList<>();
        for (Row row : await(observer.preparedQuery("SELECT entry_type || ' ' || payload_json::text || ' '"
                        + " || COALESCE(outcome, '') FROM workflow_history WHERE workflow_id = $1"
                        + " ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            texts.add(row.getString(0));
        }
        return texts;
    }

    /**
     * Ends the instance as another command's segment would: under the execution lock it writes a settled command at
     * the end of the queue, sets the instance {@code COMPLETED} and writes the history entry of the end.
     *
     * @param id the instance
     */
    void endInstanceThroughFixtureCommand(WorkflowInstanceId id) {
        int[] key = ExecutionLock.keyOf(id);
        await(observer.withTransaction(connection -> connection
                .preparedQuery("SELECT pg_advisory_xact_lock($1::int4, $2::int4)")
                .execute(Tuple.of(key[0], key[1]))
                .compose(ignored -> connection
                        .preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, state, recorded_at,"
                                + " available_at, settled_at) SELECT $1, COALESCE(MAX(seq), 0) + 1, 'SIGNAL',"
                                + " 'COMPLETE', clock_timestamp(), clock_timestamp(), clock_timestamp()"
                                + " FROM workflow_commands WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .compose(ignored -> connection
                        .preparedQuery("UPDATE workflow_command_queues SET next_seq = next_seq + 1"
                                + " WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .compose(ignored -> connection
                        .preparedQuery("UPDATE workflow_instances SET status = 'COMPLETED',"
                                + " completed_at = clock_timestamp() WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .compose(ignored -> connection
                        .preparedQuery("INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json)"
                                + " SELECT $1, COALESCE(MAX(sequence), 0) + 1, 'COMPLETED', '{}'::jsonb"
                                + " FROM workflow_history WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .mapEmpty()));
    }

    /** A transaction of the test that holds an execution lock until released. */
    static final class HeldLock implements AutoCloseable {
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean released;

        private HeldLock(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        /** Rolls the transaction back, which releases the lock. */
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            await(transaction.rollback());
            await(connection.close());
        }

        @Override
        public void close() {
            release();
        }
    }

    /** The database clock minus the clock of this JVM, in milliseconds, measured once. */
    long databaseClockOffsetMillis() {
        long before = System.currentTimeMillis();
        long database = await(observer.query("SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint")
                        .execute())
                .iterator()
                .next()
                .getLong(0);
        long after = System.currentTimeMillis();
        return database - (before + after) / 2;
    }

    /** The log events at {@code level} or above that the workflow modules logged since the last reset. */
    List<ILoggingEvent> logged(Level level) {
        List<ILoggingEvent> events = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            if (event.getLevel().isGreaterOrEqual(level)) {
                events.add(event);
            }
        }
        return events;
    }

    /** Waits until the condition holds, polling every 20 ms. */
    static void awaitCondition(String description, Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + description);
            }
            sleep(20);
        }
    }

    // --- Fixture workflows ---

    /** A registry that holds the fixture workflows. */
    DefaultWorkflowRegistry newRegistry() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(skeleton());
        registry.register(late());
        registry.register(atomic());
        registry.register(loop());
        return registry;
    }

    /**
     * Records the fixture workflow as an available code definition in the durable registry, with the plan hash of
     * the fixture's own plan, and does not activate it.
     *
     * @param store        the durable registry
     * @param definitionId the fixture workflow
     * @return the key of the recorded row
     */
    DefinitionKey recordAvailable(PgWorkflowDefinitionRegistryStore store, String definitionId) {
        RuntimeWorkflow runtime = newRegistry().allRegistered().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1L);
        Instant now = Instant.now();
        await(store.upsertCode(
                new WorkflowDefinitionRecord(
                        key,
                        DefinitionKind.CODE,
                        runtime.contractName(),
                        runtime.plan().stateTypeName(),
                        runtime.plan().planHash(),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance("code", "test", null, null, null, now),
                        null,
                        now,
                        now,
                        Map.of(),
                        null),
                false));
        return key;
    }

    private WorkflowDefinition<FlowState, SkeletonContract> skeleton() {
        return new Def<>(SKELETON, SkeletonContract.class, wf -> {
            init(wf).initialStep("route")
                    .decide("route", state -> "charge")
                    .dispatch("charge", "charge.service", ExecutorFixture::payloadOf, "paid");
            paidWait(wf);
        });
    }

    private WorkflowDefinition<FlowState, LateContract> late() {
        return new Def<>(LATE, LateContract.class, wf -> {
            init(wf).initialStep("first")
                    .decide("first", state -> "charge-1")
                    .dispatch("charge-1", "charge.service", ExecutorFixture::payloadOf, "second")
                    .decide("second", state -> "third")
                    .decide("third", state -> "charge-2")
                    .dispatch("charge-2", "charge.service", ExecutorFixture::payloadOf, "paid");
            paidWait(wf);
        });
    }

    private WorkflowDefinition<FlowState, AtomicContract> atomic() {
        return new Def<>(ATOMIC, AtomicContract.class, wf -> {
            init(wf).initialStep("first")
                    .decide("first", state -> "second")
                    .decide("second", state -> "charge-1")
                    .dispatch("charge-1", "charge.service", ExecutorFixture::payloadOf, "charge-2")
                    .dispatch("charge-2", "charge.service", ExecutorFixture::payloadOf, "paid");
            paidWait(wf);
        });
    }

    private WorkflowDefinition<FlowState, LoopContract> loop() {
        return new Def<>(LOOP, LoopContract.class, wf -> {
            init(wf).initialStep("again").decide("again", state -> "again");
            paidWait(wf);
        });
    }

    private WorkflowBuilder<FlowState> init(WorkflowBuilder<FlowState> wf) {
        return wf.init(FlowStart.class, start -> {
            initialStates.add(start.ref());
            onInitialState.accept(start.ref());
            return new FlowState(start.ref());
        });
    }

    private static void paidWait(WorkflowBuilder<FlowState> wf) {
        wf.waitForSignal("paid", "paid", String.class)
                .onSignal((state, payload) -> state)
                .toStepOnSignal("done")
                .build()
                .complete("done");
    }

    private static Map<String, Object> payloadOf(FlowState state) {
        return Map.of("ref", state.ref());
    }

    private static final class Def<C> implements WorkflowDefinition<FlowState, C> {
        private final String id;
        private final Class<C> contract;
        private final Consumer<WorkflowBuilder<FlowState>> body;

        Def(String id, Class<C> contract, Consumer<WorkflowBuilder<FlowState>> body) {
            this.id = id;
            this.contract = contract;
            this.body = body;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<FlowState> stateType() {
            return FlowState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<FlowState> wf) {
            body.accept(wf);
        }
    }

    // --- The recorder of service calls ---

    /**
     * Writes a row for every service call through the transaction it is handed. A call may stall in the gate first,
     * and a call of a poisoned instance's second dispatch fails after it wrote.
     */
    private final class IntentRecorder implements WorkflowSideEffectRecorder<SqlClient> {

        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            String ref = (String) ((Map<?, ?>) intent.payload()).get("ref");
            String step = intent.correlation().stepId();
            UUID workflowId = intent.correlation().workflowId().value();
            intentCalls.add(new IntentCall(workflowId, step, ref));
            return gate.route(intent, tx)
                    .compose(ignored -> tx.preparedQuery(
                                    "INSERT INTO executor_it_outbox (workflow_id, step_id) VALUES ($1, $2)")
                            .execute(Tuple.of(workflowId, step)))
                    .compose(ignored -> {
                        if (ref.startsWith(poisonPrefix) && "charge-2".equals(step)) {
                            recorderFailures.incrementAndGet();
                            return Future.<RecorderResult>failedFuture(new IllegalStateException("recorder failed"));
                        }
                        return Future.<RecorderResult>succeededFuture(RecorderResult.empty());
                    });
        }
    }

    /** Collects the workflow events of every segment; the events join the segment's transaction like any intent. */
    private final class EventRecorder implements WorkflowSideEffectRecorder<SqlClient> {

        @Override
        public IntentKind kind() {
            return IntentKind.WORKFLOW_EVENT;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            workflowEvents.add((WorkflowEventIntent) intent.payload());
            return Future.succeededFuture(RecorderResult.empty());
        }
    }

    // --- The gate ---

    /**
     * Stalls one service call of one segment until the test opens it. A state callback arms it; the recorder routes
     * every call through it. It stalls at most once, so a later segment of the same command passes.
     */
    static final class SegmentGate {
        private final AtomicBoolean spent = new AtomicBoolean();
        private final AtomicInteger seen = new AtomicInteger();
        private final List<Promise<Void>> waiting = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> stalled = new CompletableFuture<>();
        private volatile int stallOnCall;
        private volatile boolean open;
        private volatile Function<SqlClient, Future<Void>> afterOpen = client -> Future.<Void>succeededFuture();

        /** Stalls the {@code call}-th service call from now, the first time it is armed; later arming is ignored. */
        void armOnCall(int call) {
            if (spent.compareAndSet(false, true)) {
                seen.set(0);
                stallOnCall = call;
            }
        }

        /** Runs {@code work} on the client the stalled call was handed, once the gate opens and before it resumes. */
        void afterOpen(Function<SqlClient, Future<Void>> work) {
            this.afterOpen = work;
        }

        /** Completes when a call stalls in the gate. */
        CompletableFuture<Void> stalled() {
            return stalled;
        }

        /** Lets the stalled call, and every later one, continue. */
        void open() {
            open = true;
            for (Promise<Void> promise : waiting) {
                promise.tryComplete();
            }
        }

        Future<Void> route(WorkflowSideEffectIntent intent, SqlClient tx) {
            if (stallOnCall == 0 || open || seen.incrementAndGet() != stallOnCall) {
                return Future.succeededFuture();
            }
            ContextInternal context = (ContextInternal) Vertx.currentContext();
            Promise<Void> promise = context.promise();
            waiting.add(promise);
            stalled.complete(null);
            if (open) {
                promise.tryComplete();
            }
            return promise.future().compose(ignored -> afterOpen.apply(tx));
        }
    }

    // --- The recording runner ---

    /** One call of the runner's deadline overload. */
    static final class DeadlineCall {
        final long startedNanos = System.nanoTime();
        final Duration duration;
        volatile Throwable failure;
        volatile boolean succeeded;

        DeadlineCall(Duration duration) {
            this.duration = duration;
        }

        boolean hitDeadline() {
            Throwable t = failure;
            for (int depth = 0; t != null && depth < 10; depth++) {
                if (t instanceof TransactionDeadlineExceededException) {
                    return true;
                }
                t = t.getCause();
            }
            return false;
        }
    }

    /**
     * Wraps a transaction runner and records, for every call of the deadline overload, the duration it was given and
     * how it ended. It can hold the first call until the test releases it.
     */
    static final class DeadlineRecordingRunner implements WorkflowTransactionRunner<SqlClient> {
        private final WorkflowTransactionRunner<SqlClient> delegate;
        private final boolean holdFirst;
        private final AtomicBoolean heldAlready = new AtomicBoolean();
        private final CompletableFuture<Void> held = new CompletableFuture<>();
        private volatile Promise<Void> release;
        final List<DeadlineCall> calls = new CopyOnWriteArrayList<>();

        DeadlineRecordingRunner(WorkflowTransactionRunner<SqlClient> delegate, boolean holdFirst) {
            this.delegate = delegate;
            this.holdFirst = holdFirst;
        }

        @Override
        public <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
            return delegate.inTransaction(level, work);
        }

        @Override
        public <R> Future<R> inTransaction(
                @Nullable IsolationLevel level, Duration deadline, Function<SqlClient, Future<R>> work) {
            DeadlineCall call = new DeadlineCall(deadline);
            calls.add(call);
            Future<Void> before = Future.succeededFuture();
            if (holdFirst && heldAlready.compareAndSet(false, true)) {
                Promise<Void> promise = ((ContextInternal) Vertx.currentContext()).promise();
                release = promise;
                held.complete(null);
                before = promise.future();
            }
            return before.compose(ignored -> delegate.<R>inTransaction(level, deadline, work))
                    .onComplete(result -> {
                        call.failure = result.cause();
                        call.succeeded = result.succeeded();
                    });
        }

        /** Completes when the first call is held. */
        CompletableFuture<Void> held() {
            return held;
        }

        /** Lets the held first call go on. */
        void releaseHeldCall() {
            Promise<Void> promise = release;
            if (promise != null) {
                promise.tryComplete();
            }
        }
    }

    // --- Decorating a command repository ---

    /** What a decorated member does with a call: the call number from 1, the arguments and the real answer. */
    @FunctionalInterface
    interface Interception {
        Object answer(
                int call,
                java.lang.reflect.Method member,
                Object[] arguments,
                java.util.concurrent.Callable<Object> real)
                throws Exception;
    }

    /**
     * Decorates a command repository: every call of a member with the given name goes to the interception, every other
     * call goes to the real repository.
     *
     * @param member       the name of the intercepted member
     * @param interception what to do with a call of it
     * @return the decorator
     */
    @SuppressWarnings("unchecked")
    static UnaryOperator<WorkflowCommandRepository<SqlClient>> intercepting(String member, Interception interception) {
        return real -> {
            AtomicInteger calls = new AtomicInteger();
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, arguments) -> {
                        java.util.concurrent.Callable<Object> forward = () -> {
                            try {
                                return method.invoke(real, arguments);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() instanceof Exception cause ? cause : e;
                            }
                        };
                        if (!method.getName().equals(member)) {
                            return forward.call();
                        }
                        return interception.answer(calls.incrementAndGet(), method, arguments, forward);
                    });
        };
    }

    /**
     * The answer of a member that returns a future of an update count and reports that no row was updated: zero for a
     * count, {@code false} for a flag.
     *
     * @param member the intercepted member
     * @return a succeeded future of zero or {@code false}
     */
    static Object noRowUpdated(java.lang.reflect.Method member) {
        if (member.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] == Boolean.class) {
            return Future.succeededFuture(false);
        }
        if (member.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] == Long.class) {
            return Future.succeededFuture(0L);
        }
        return Future.succeededFuture(0);
    }

    // --- Context carriage ---

    private static DurableContextMetadataEncoder<CorrelationCtx> correlationEncoder() {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<CorrelationCtx> type() {
                return CorrelationCtx.class;
            }

            @Override
            public String namespace() {
                return CORRELATION_NAMESPACE;
            }

            @Override
            public DurableMetadata encode(CorrelationCtx value, DurableEncodeContext context) {
                return DurableMetadata.of(
                        CORRELATION_NAMESPACE, new JsonObject().put("correlationId", value.correlationId()));
            }
        };
    }

    private static DurableContextMetadataDecoder<CorrelationCtx> correlationDecoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<CorrelationCtx> type() {
                return CorrelationCtx.class;
            }

            @Override
            public String namespace() {
                return CORRELATION_NAMESPACE;
            }

            @Override
            public ContextDecodeResult<CorrelationCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(CORRELATION_NAMESPACE)
                        .map(body -> body.getString("correlationId"))
                        .map(CorrelationCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
    }

    private static DurableContextMetadataEncoder<IdentitySnapshotCtx> identityEncoder() {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<IdentitySnapshotCtx> type() {
                return IdentitySnapshotCtx.class;
            }

            @Override
            public String namespace() {
                return IDENTITY_NAMESPACE;
            }

            @Override
            public DurableMetadata encode(IdentitySnapshotCtx value, DurableEncodeContext context) {
                return DurableMetadata.of(IDENTITY_NAMESPACE, new JsonObject().put("subject", value.subject()));
            }
        };
    }

    private static DurableContextMetadataDecoder<IdentitySnapshotCtx> identityDecoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IdentitySnapshotCtx> type() {
                return IdentitySnapshotCtx.class;
            }

            @Override
            public String namespace() {
                return IDENTITY_NAMESPACE;
            }

            @Override
            public ContextDecodeResult<IdentitySnapshotCtx> decode(
                    DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(IDENTITY_NAMESPACE)
                        .map(body -> body.getString("subject"))
                        .map(IdentitySnapshotCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
    }

    /**
     * Whoever calls with an authenticated identity: binds correlation {@code X}, tenant {@code T} and an identity
     * snapshot of a user in the durable context of the current duplicated Vert.x context, and runs the work in it.
     */
    final class IdentityFixture {

        /** The correlation identifier of the authenticated caller. */
        static final String CORRELATION = "X";

        /** The tenant of the authenticated caller. */
        static final String TENANT = "T";

        /** The subject of the authenticated caller's identity snapshot. */
        static final String SUBJECT = "user-77";

        /**
         * Binds the authenticated caller's context on the current context and runs {@code work}.
         *
         * @param work the work to run while the context is bound
         * @param <T>  the result type
         * @return the work's result
         */
        <T> Future<T> authenticatedCaller(java.util.function.Supplier<Future<T>> work) {
            var correlation = holder.bind(CorrelationCtx.class, new CorrelationCtx(CORRELATION));
            var tenant = holder.bind(TenantCtx.class, new TenantCtx(TENANT));
            var identity = holder.bind(IdentitySnapshotCtx.class, new IdentitySnapshotCtx(SUBJECT));
            return work.get().onComplete(ignored -> {
                identity.close();
                tenant.close();
                correlation.close();
            });
        }
    }

    IdentityFixture identity() {
        return new IdentityFixture();
    }

    // --- Plumbing ---

    private Pool pool(Vertx owner, int size) {
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(size))
                .connectingTo(new PgConnectOptions()
                        .setHost(db.host())
                        .setPort(db.port())
                        .setDatabase(db.database())
                        .setUser(db.user())
                        .setPassword(db.password()))
                .using(owner)
                .build();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    static <T> T await(Future<T> future) {
        return await(future, AWAIT);
    }

    static <T> T await(Future<T> future, Duration timeout) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("future failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    static Throwable failureOf(Future<?> future, Duration timeout) {
        try {
            future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
        throw new AssertionError("expected the future to fail but it succeeded");
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // closing is best effort
        }
    }
}
