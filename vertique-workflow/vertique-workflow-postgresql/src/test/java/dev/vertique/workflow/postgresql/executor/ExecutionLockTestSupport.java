// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Engine assembly, fixture definitions and two-session helpers shared by the execution-lock integration tests.
 *
 * <p>The assembly is the production wiring (the PostgreSQL module over a pool) plus fixture definitions, a timer
 * recorder that writes real timer rows and a no-op event recorder. The recording configuration is supplied per
 * assembly, so one test class can run an engine with a short lock timeout next to one with a long timeout over the
 * same database.
 */
final class ExecutionLockTestSupport {

    /** How long a test waits for a future that must complete. */
    static final Duration AWAIT = Duration.ofSeconds(20);

    /** How long a test polls for a backend to start waiting for a lock. */
    static final Duration BLOCK_WAIT = Duration.ofSeconds(10);

    /** A lock timeout long enough that a test never hits it by accident. */
    static final long LONG_LOCK_TIMEOUT_MS = 20_000L;

    static final String TICK = "lock-tick";
    static final String FORK = "lock-fork";
    static final String MIGRATING = "lock-migrating";
    static final String TASK = "lock-task";

    private ExecutionLockTestSupport() {}

    // --- Fixture types ---

    /**
     * Start payload of every fixture definition.
     *
     * @param id the label of the instance
     */
    record LockStart(String id) {}

    /**
     * State of every fixture definition.
     *
     * @param id    the label of the instance
     * @param count how many signals the state has absorbed
     */
    record LockState(String id, int count) {}

    /** Contract of the definition that waits for a signal again and again. */
    interface TickContract {
        @WorkflowSignal("tick")
        Future<Void> tick(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the definition that forks into two signal waits. */
    interface ForkContract {
        @WorkflowSignal("signal-a")
        Future<Void> signalA(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("signal-b")
        Future<Void> signalB(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the two versions of the migrating definition. */
    interface MigratingContract {
        @WorkflowSignal("pause")
        Future<Void> pause(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Contract of the definition with a task. */
    interface TaskContract {}

    private static final class Def<C> implements WorkflowDefinition<LockState, C> {
        private final String id;
        private final long version;
        private final Class<C> contract;
        private final Consumer<WorkflowBuilder<LockState>> body;

        Def(String id, long version, Class<C> contract, Consumer<WorkflowBuilder<LockState>> body) {
            this.id = id;
            this.version = version;
            this.contract = contract;
            this.body = body;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<LockState> stateType() {
            return LockState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<LockState> wf) {
            body.accept(wf);
        }
    }

    /** Waits for {@code tick} and waits again, so any number of signals is accepted one after the other. */
    private static Def<TickContract> tickDefinition() {
        return new Def<>(TICK, 1L, TickContract.class, wf -> wf.init(LockStart.class, s -> new LockState(s.id(), 0))
                .initialStep("wait")
                .waitForSignal("wait", "tick", String.class)
                .onSignal((s, p) -> new LockState(s.id(), s.count() + 1))
                .toStepOnSignal("wait")
                .build()
                .complete("done"));
    }

    /** Forks into two signal-wait branches that join into completion. */
    private static Def<ForkContract> forkDefinition() {
        return new Def<>(FORK, 1L, ForkContract.class, wf -> {
            wf.init(LockStart.class, s -> new LockState(s.id(), 0))
                    .fork("fork")
                    .branch("a", "wait-a")
                    .branch("b", "wait-b")
                    .join("join")
                    .waitForSignal("wait-a", "signal-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-a")
                    .build()
                    .complete("complete-a")
                    .waitForSignal("wait-b", "signal-b", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        });
    }

    /** Version 1 of the migrating definition waits for a signal. */
    private static Def<MigratingContract> migratingV1() {
        return new Def<>(
                MIGRATING, 1L, MigratingContract.class, wf -> wf.init(LockStart.class, s -> new LockState(s.id(), 0))
                        .initialStep("wait-v1")
                        .waitForSignal("wait-v1", "pause", String.class)
                        .onSignal((s, p) -> s)
                        .toStepOnSignal("done-v1")
                        .build()
                        .complete("done-v1"));
    }

    /** Version 2 of the migrating definition completes at once. */
    private static Def<MigratingContract> migratingV2() {
        return new Def<>(
                MIGRATING, 2L, MigratingContract.class, wf -> wf.init(LockStart.class, s -> new LockState(s.id(), 0))
                        .initialStep("init-v2")
                        .complete("init-v2"));
    }

    /** A task with a due date and a reminder, neither of which fires on its own in these tests. */
    private static Def<TaskContract> taskDefinition() {
        return new Def<>(TASK, 1L, TaskContract.class, wf -> wf.init(LockStart.class, s -> new LockState(s.id(), 0))
                .initialStep("review")
                .task("review")
                .assignToRole("compliance")
                .decision("approve", String.class)
                .onDecision((s, p) -> s)
                .toStep("done")
                .dueIn(Duration.ofHours(1))
                .onDue(s -> s)
                .toStepOnDue("escalate")
                .reminderAt(Duration.ofHours(2))
                .build()
                .complete("done")
                .complete("escalate"));
    }

    private static final class LockMigration implements WorkflowMigrationHandler<LockState, LockState> {
        @Override
        public String definitionId() {
            return MIGRATING;
        }

        @Override
        public long fromDefinitionVersion() {
            return 1L;
        }

        @Override
        public long targetDefinitionVersion() {
            return 2L;
        }

        @Override
        public Class<LockState> sourceStateType() {
            return LockState.class;
        }

        @Override
        public Class<LockState> targetStateType() {
            return LockState.class;
        }

        @Override
        public MigrationResult<LockState> migrate(LockState sourceState, MigrationContext ctx) {
            return MigrationResult.anchorAtInitial(new LockState(sourceState.id(), sourceState.count() + 1));
        }
    }

    // --- Assembly ---

    @Module
    static class FixtureModule {

        @Provides
        @IntoSet
        static WorkflowContributor fixtureDefinitions() {
            return registry -> {
                registry.register(tickDefinition());
                registry.register(forkDefinition());
                registry.register(migratingV1());
                registry.register(migratingV2());
                registry.register(taskDefinition());
            };
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper exceptionMapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        @Singleton
        static WorkflowMigrationRegistry migrationRegistry(WorkflowRegistry registry) {
            return new DefaultWorkflowMigrationRegistry(Set.of(new LockMigration()), registry);
        }

        /** Satisfies the rule that a plan with task reminders needs an event recorder; emits nothing. */
        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> eventRecorder() {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.WORKFLOW_EVENT;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }
    }

    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                ContextRuntimeModule.class,
                LoggingContextModule.class,
                dev.vertique.workflow.postgresql.engine.ExecutorDeployment.FastPolling.class,
                FixtureModule.class
            })
    interface LockComponent {

        java.util.Set<dev.vertique.deploy.VerticleDeployment> verticleDeployments();

        WorkflowOperations operations();

        TransactionalWorkflowOperations<SqlClient> transactionalOperations();

        TransactionalTaskCallbacks<SqlClient> taskCallbacks();

        WorkflowExecutionProbe probe();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        WorkflowCommandRepository<SqlClient> commands();

        BranchTokenRepository<SqlClient> branchTokens();

        WorkflowInstanceRepository<SqlClient> instances();

        WorkflowRegistry registry();

        PgWorkflowDefinitionRegistryStore definitionStore();

        WorkflowDefinitionLifecycle definitionLifecycle();

        @Component.Factory
        interface Factory {
            LockComponent create(
                    @BindsInstance Pool pool,
                    @BindsInstance Clock clock,
                    @BindsInstance WorkflowRecordingConfig config);
        }
    }

    /**
     * An engine over a pool with the fixture definitions, and the fixtures that start instances in a known wait.
     *
     * @param component the assembled graph
     * @param pool      the pool the graph runs on, also used to observe the database
     * @param vertx     the Vert.x instance that owns the pool
     * @param executor  the deployed workflow executor of the graph, which runs the starts that tests record
     */
    record Assembly(
            LockComponent component,
            Pool pool,
            Vertx vertx,
            dev.vertique.workflow.postgresql.engine.ExecutorDeployment executor) {

        /**
         * Assembles the engine with the given lock timeout.
         *
         * @param vertx         the Vert.x instance that owns the pool
         * @param pool          the pool of the database under test
         * @param lockTimeoutMs the time a requester waits for an instance's execution lock
         * @param seed          whether to record and activate the fixture definitions; once per database
         * @return the assembly
         */
        static Assembly open(Vertx vertx, Pool pool, long lockTimeoutMs, boolean seed) {
            LockComponent component = DaggerExecutionLockTestSupport_LockComponent.factory()
                    .create(pool, Clock.systemUTC(), new WorkflowRecordingConfig(lockTimeoutMs));
            if (seed) {
                seedDefinitions(component);
            }
            // The graph runs no deployer: only its workflow executor is deployed
            dev.vertique.workflow.postgresql.engine.ExecutorDeployment executor =
                    await(dev.vertique.workflow.postgresql.engine.ExecutorDeployment.deploy(
                            vertx, component.verticleDeployments()));
            return new Assembly(component, pool, vertx, executor);
        }

        /** Undeploys the graph's executor; call it before the pool closes. */
        void stopExecutor() {
            await(executor.undeploy());
        }

        WorkflowCommandRepository<SqlClient> commands() {
            return component.commands();
        }

        WorkflowTransactionRunner<SqlClient> runner() {
            return component.transactionRunner();
        }

        /** Starts an instance of {@code definitionId} on {@code version} (or the current one) and waits until idle. */
        WorkflowInstanceId start(String definitionId, Class<?> contract, Long version) {
            String key = definitionId + "-" + UUID.randomUUID();
            StartCommand command = new StartCommand(
                    definitionId, contract.getName(), new LockStart(key), key, null, null, version, null, null);
            WorkflowInstanceId id =
                    await(inContext(vertx, () -> component.operations().start(command)));
            await(component.probe().awaitIdle(id, Duration.ofSeconds(10)));
            return id;
        }

        /** An instance that waits for {@code tick} signals. */
        WorkflowInstanceId startTick() {
            return start(TICK, TickContract.class, null);
        }

        /** An instance that has forked into the branches {@code a} and {@code b}, both waiting for a signal. */
        WorkflowInstanceId startFork() {
            return start(FORK, ForkContract.class, null);
        }

        /** An instance pinned to version 1 of the migrating definition, waiting for a signal. */
        WorkflowInstanceId startMigrating() {
            return start(MIGRATING, MigratingContract.class, 1L);
        }

        /** An instance waiting on an open task that has a due date and a reminder. */
        WorkflowInstanceId startTask() {
            return start(TASK, TaskContract.class, null);
        }

        /** Inserts an instance row in status {@code PENDING}, as the recording of a start leaves it. */
        WorkflowInstanceId insertPendingInstance() {
            UUID id = UUID.randomUUID();
            await(pool.preparedQuery("INSERT INTO workflow_instances"
                            + " (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)"
                            + " VALUES ($1, 'lock-pending', 1, repeat('a', 64), 'PENDING', NULL, NULL)")
                    .execute(Tuple.of(id)));
            return new WorkflowInstanceId(id);
        }

        /** The wait key of an instance that waits on a task or a timer. */
        UUID waitKeyOf(WorkflowInstanceId id) {
            Row row = await(pool.preparedQuery("SELECT wait_key FROM workflow_instances WHERE id = $1")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next();
            return UUID.fromString(row.getString(0));
        }

        /** Makes a waiting instance a failed one, as an exhausted step leaves it. */
        void failInstance(WorkflowInstanceId id) {
            await(pool.preparedQuery("UPDATE workflow_instances SET status = 'FAILED', wait_type = NULL,"
                            + " wait_key = NULL, wait_aux_id = NULL, error_type = 'IT', error_message = 'failed'"
                            + " WHERE id = $1")
                    .execute(Tuple.of(id.value())));
        }
    }

    /**
     * Records every default-scope version the registry holds and activates the highest of each id, the way an operator
     * would, so that a start without a version has a current version to select.
     */
    private static void seedDefinitions(LockComponent component) {
        Instant now = Instant.now();
        component.registry().codeDefinitions().stream()
                .filter(runtime -> WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey()))
                .sorted(Comparator.comparing(
                                (RuntimeWorkflow runtime) -> runtime.plan().definitionId())
                        .thenComparingLong(runtime -> runtime.plan().definitionVersion()))
                .forEach(runtime -> {
                    String id = runtime.plan().definitionId();
                    long version = runtime.plan().definitionVersion();
                    await(component
                            .definitionStore()
                            .upsertCode(new WorkflowDefinitionRecord(
                                    new DefinitionKey(WorkflowScopes.DEFAULT, id, version),
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
                                    null)));
                    await(component
                            .definitionLifecycle()
                            .activate(WorkflowScopes.DEFAULT, id, WorkflowScopes.DEFAULT, version));
                });
    }

    /** Builds a pool of the given size for the database of a test class. */
    static Pool pool(Vertx vertx, DbPoolConfig config, int size) {
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

    /** Runs the action on a duplicated Vert.x context, as every production dispatch entry does. */
    static <T> Future<T> inContext(Vertx vertx, Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            try {
                action.get().onComplete(result);
            } catch (Throwable failure) {
                result.tryFail(failure);
            }
        });
        return result.future();
    }

    // --- Awaiting ---

    /** Waits for the future and returns its result; a failure is rethrown as is. */
    static <T> T await(Future<T> future) {
        return await(future, AWAIT);
    }

    /** Waits at most {@code timeout} for the future and returns its result; a failure is rethrown as is. */
    static <T> T await(Future<T> future, Duration timeout) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError("future failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a future", e);
        }
    }

    /** Waits for a future that must fail, and returns the failure. */
    static Throwable failureOf(Future<?> future) {
        try {
            Object result = await(future);
            throw new AssertionError("expected a failure but the future succeeded with " + result);
        } catch (AssertionError e) {
            throw e;
        } catch (RuntimeException e) {
            return e;
        }
    }

    // --- Sessions ---

    /**
     * Transactions held open on separate connections, so that a test decides when each commits, and observation of
     * what each holds or waits for in {@code pg_locks}.
     *
     * <p>A held transaction runs through the engine's transaction runner, so a failure that ends it is mapped to
     * the workflow exception a caller would see. Whatever is still open when the object is closed is rolled back.
     */
    static final class TwoSessions implements AutoCloseable {

        private final WorkflowTransactionRunner<SqlClient> runner;
        private final Pool observer;
        private final Vertx vertx;
        private final List<Held<?>> open = new ArrayList<>();

        TwoSessions(WorkflowTransactionRunner<SqlClient> runner, Pool observer, Vertx vertx) {
            this.runner = runner;
            this.observer = observer;
            this.vertx = vertx;
        }

        /**
         * Opens a transaction, runs {@code work} in it and keeps the transaction open until it is committed or rolled
         * back.
         */
        <T> Held<T> holdOpen(Function<SqlClient, Future<T>> work) {
            Held<T> held = new Held<>(vertx, runner, work);
            open.add(held);
            return held;
        }

        /**
         * Waits until the transaction waits for an advisory lock that it has not been granted. Fails at once when the
         * work of the transaction has finished without ever waiting.
         */
        void awaitBlocked(Held<?> held) {
            awaitBlocked(held.pid(), held.workFuture());
        }

        /**
         * Waits until the backend waits for an advisory lock that it has not been granted. Fails at once when
         * {@code pending}, the work the backend runs, has finished without ever waiting.
         */
        void awaitBlocked(int backendPid, Future<?> pending) {
            String waiting = "SELECT 1 FROM pg_locks WHERE pid = $1 AND locktype = 'advisory' AND NOT granted";
            long deadline = System.nanoTime() + BLOCK_WAIT.toNanos();
            while (query(waiting, backendPid).isEmpty()) {
                if (pending.isComplete()) {
                    throw new AssertionError(
                            "backend " + backendPid + " finished its work without waiting for an" + " advisory lock");
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("backend " + backendPid + " never waited for an advisory lock");
                }
                await(vertx.timer(10));
            }
        }

        /** Waits until one of the futures has completed, successfully or not. */
        void awaitAnyComplete(Future<?>... futures) {
            long deadline = System.nanoTime() + AWAIT.toNanos();
            while (true) {
                for (Future<?> future : futures) {
                    if (future.isComplete()) {
                        return;
                    }
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("none of the futures completed");
                }
                await(vertx.timer(10));
            }
        }

        /** The number of advisory locks any backend waits for. */
        long ungrantedAdvisoryLocks() {
            return query("SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                    .size();
        }

        /** The two-integer advisory locks the backend holds, each as the pair (first integer, second integer). */
        List<long[]> twoIntegerLocksHeldBy(int backendPid) {
            List<long[]> held = new ArrayList<>();
            for (Row row : query(
                    "SELECT classid::bigint, objid::bigint FROM pg_locks"
                            + " WHERE pid = $1 AND locktype = 'advisory' AND objsubid = 2 AND granted",
                    backendPid)) {
                held.add(new long[] {row.getLong(0), row.getLong(1)});
            }
            return held;
        }

        /** The one-bigint advisory locks the backend holds. */
        long bigintLocksHeldBy(int backendPid) {
            return query(
                            "SELECT 1 FROM pg_locks WHERE pid = $1 AND locktype = 'advisory' AND objsubid = 1"
                                    + " AND granted",
                            backendPid)
                    .size();
        }

        private List<Row> query(String sql, Object... params) {
            List<Row> rows = new ArrayList<>();
            await(observer.preparedQuery(sql).execute(Tuple.from(List.of(params))))
                    .forEach(rows::add);
            return rows;
        }

        @Override
        public void close() {
            for (Held<?> held : open) {
                held.abandon();
            }
        }
    }

    /**
     * One open transaction.
     *
     * @param <T> the result of the work the transaction was opened with
     */
    static final class Held<T> {

        private final Promise<Integer> backendPid = Promise.promise();
        private final Promise<SqlClient> connection = Promise.promise();
        private final Promise<T> result = Promise.promise();
        private final Promise<Void> gate = Promise.promise();
        private final Future<Void> outcome;

        private final Vertx vertx;

        Held(Vertx vertx, WorkflowTransactionRunner<SqlClient> runner, Function<SqlClient, Future<T>> work) {
            this.vertx = vertx;
            this.outcome = inContext(
                    vertx,
                    () -> runner.<Void>inTransaction(null, tx -> tx.query("SELECT pg_backend_pid()")
                            .execute()
                            .compose(rows -> {
                                backendPid.complete(rows.iterator().next().getInteger(0));
                                return work.apply(tx);
                            })
                            .compose(value -> {
                                connection.complete(tx);
                                result.complete(value);
                                return gate.future();
                            })
                            .onFailure(failure -> {
                                backendPid.tryFail(failure);
                                result.tryFail(failure);
                                connection.tryFail(failure);
                            })));
        }

        /** The backend process id of the connection of this transaction. */
        int pid() {
            return await(backendPid.future());
        }

        /** Waits until the work has finished and returns its result; the transaction is still open. */
        T ready() {
            return await(result.future());
        }

        /** The work this transaction was opened with. */
        Future<T> workFuture() {
            return result.future();
        }

        /** Waits at most {@code timeout} until the work has finished and returns its result. */
        T ready(Duration timeout) {
            return await(result.future(), timeout);
        }

        /** The failure of the work, waited for. */
        Throwable workFailure() {
            return failureOf(result.future());
        }

        /** The end of the transaction: succeeds on commit, fails with the mapped failure on rollback. */
        Future<Void> outcome() {
            return outcome;
        }

        /** The connection of this transaction, once the work has finished. */
        SqlClient connection() {
            return await(connection.future());
        }

        /** Runs more work in the open transaction. */
        <R> Future<R> run(Function<SqlClient, Future<R>> more) {
            return connection.future().compose(tx -> inContext(vertx, () -> more.apply(tx)));
        }

        /** Commits the transaction and waits for the commit. */
        void commit() {
            gate.tryComplete();
            await(outcome);
        }

        /** Rolls the transaction back and waits for it. */
        void rollback() {
            gate.tryFail(new IllegalStateException("rolled back by the test"));
            try {
                await(outcome);
            } catch (RuntimeException expected) {
                // a rollback is reported as the failure that caused it
            }
        }

        /**
         * Rolls the transaction back because of {@code cause}, as a caller whose statement failed would, and returns
         * the failure the transaction runner reports for it.
         */
        Throwable rollbackBecauseOf(Throwable cause) {
            gate.tryFail(cause);
            return failureOf(outcome);
        }

        private void abandon() {
            if (!outcome.isComplete()) {
                gate.tryFail(new IllegalStateException("closed by the test"));
                try {
                    await(outcome, Duration.ofSeconds(10));
                } catch (RuntimeException | AssertionError ignored) {
                    // the point is that the transaction is over
                }
            }
        }
    }
}
