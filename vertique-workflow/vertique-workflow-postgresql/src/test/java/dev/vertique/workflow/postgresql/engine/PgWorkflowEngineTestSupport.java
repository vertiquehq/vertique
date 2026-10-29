// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.WorkflowEngineFactory;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.WorkflowEngineParts;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.history.WorkflowHistoryEventPublisher;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.history.WorkflowHistoryRedactor;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.postgresql.executor.PgWorkflowCommandRepository;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskReservations;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * Test-scope factory for constructing {@link WorkflowEngineHandle} instances directly from a
 * {@link Pool}.
 *
 * <p>This support type holds the PostgreSQL construction knowledge — building a
 * {@link PgWorkflowTransactionRunner} over a {@link Pool} (with its stage-1
 * {@link PgWorkflowExceptionMapper} and the relocated stage-2
 * {@link dev.vertique.workflow.engine.WorkflowExceptionMapper}) — and then delegates the assembly of
 * the entire portable collaborator graph to {@link WorkflowEngineFactory#create}. The factory owns
 * construction of the engine collaborators ({@code RecorderRouter}, {@code BranchTransitionEngine},
 * {@code ForkJoinCoordinator}, {@code FingerprintCanonicalizer}, the leaf history/event/reminder/
 * compensation services, and the {@code WorkflowEngine} facade), which are package-private in the
 * engine module and therefore not constructible from this (dialect) test scope.
 *
 * <p>Each {@code create} overload maps 1:1 to a former test-only constructor variant, but the
 * parameters that used to be pre-built engine collaborators are now the raw ingredients the factory
 * needs (the {@code Set<WorkflowSideEffectRecorder<SqlClient>>} and the optional-intent-kind set in
 * place of a {@code RecorderRouter}; the branch-token and join-state repositories in place of a
 * pre-built {@code BranchTransitionEngine} / {@code BranchCompensationOrchestrator}):
 * <ul>
 *   <li>{@link #create(Pool, WorkflowRegistry, WorkflowInstanceRepository, WorkflowHistoryRepository,
 *       WorkflowDedupRepository, Set, Set, TimerStore, TaskStore, Clock)} — omits the PRD-WF-002
 *       fork/join repositories (branch/join) and the PRD-WF-003 migration registry.</li>
 *   <li>{@link #create(Pool, WorkflowRegistry, WorkflowInstanceRepository, WorkflowHistoryRepository,
 *       WorkflowDedupRepository, Set, Set, TimerStore, TaskStore, Clock, BranchTokenRepository,
 *       JoinStateRepository)} — includes the fork/join repositories but omits the migration
 *       registry.</li>
 *   <li>{@link #create(Pool, WorkflowRegistry, WorkflowInstanceRepository, WorkflowHistoryRepository,
 *       WorkflowDedupRepository, Set, Set, TimerStore, TaskStore, Clock, BranchTokenRepository,
 *       JoinStateRepository, DurableContextPropagator, Optional)} — includes the fork/join
 *       repositories, an optional durable-context propagator, and the migration registry.</li>
 *   <li>{@link #create(Pool, WorkflowRegistry, WorkflowInstanceRepository, WorkflowHistoryRepository,
 *       WorkflowDedupRepository, Set, Set, TimerStore, TaskStore, Clock, BranchTokenRepository,
 *       JoinStateRepository, DurableContextPropagator, Optional, Optional)} — additionally binds an
 *       optional {@link BeanValidator} that switches payload constraint enforcement on.</li>
 * </ul>
 */
public final class PgWorkflowEngineTestSupport {

    private PgWorkflowEngineTestSupport() {
        // static factory holder
    }

    // --- create overloads (one per former test-only constructor variant) ---

    /**
     * Builds an engine that omits the PRD-WF-002 fork/join repositories and the PRD-WF-003 migration
     * registry. Plans containing {@link dev.vertique.workflow.plan.ForkNode} cannot execute through
     * an engine constructed this way (the dispatcher will NPE on the branch repository); legacy
     * single-path tests are unaffected. Calling {@code migrate} on an engine built without a
     * migration registry will fail with {@link UnsupportedOperationException}.
     *
     * @param pool                the connection pool the runner transacts on
     * @param registry            the workflow registry
     * @param instances           the instance repository
     * @param history             the history repository
     * @param dedup               the dedup repository
     * @param recorders           the side-effect recorders contributed by the test
     * @param optionalIntentKinds the intent kinds the recorder router treats as optional
     * @param timerStore          the timer store
     * @param taskStore           the task store
     * @param clock               the clock
     * @return a fully wired {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(
            Pool pool,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock) {
        return create(
                pool,
                registry,
                instances,
                history,
                dedup,
                recorders,
                optionalIntentKinds,
                timerStore,
                taskStore,
                clock,
                null,
                null,
                null,
                Optional.empty());
    }

    /**
     * Builds an engine that includes the PRD-WF-002 fork/join repositories but omits the PRD-WF-003
     * migration registry. Calling {@code migrate} on an engine built this way will fail with
     * {@link UnsupportedOperationException}.
     *
     * @param pool                the connection pool the runner transacts on
     * @param registry            the workflow registry
     * @param instances           the instance repository
     * @param history             the history repository
     * @param dedup               the dedup repository
     * @param recorders           the side-effect recorders contributed by the test
     * @param optionalIntentKinds the intent kinds the recorder router treats as optional
     * @param timerStore          the timer store
     * @param taskStore           the task store
     * @param clock               the clock
     * @param branchTokens        the branch token repository
     * @param joinStates          the join state repository
     * @return a fully wired {@link WorkflowEngineHandle}
     */
    static WorkflowEngineHandle create(
            Pool pool,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates) {
        return create(
                pool,
                registry,
                instances,
                history,
                dedup,
                recorders,
                optionalIntentKinds,
                timerStore,
                taskStore,
                clock,
                branchTokens,
                joinStates,
                null,
                Optional.empty());
    }

    /**
     * Builds an engine that includes the PRD-WF-002 fork/join repositories, an optional
     * durable-context propagator, and the PRD-WF-003 migration registry. Builds the transaction
     * runner from {@code pool} via {@link #runnerFor(Pool)} so the layered exception mapping is
     * exercised; used by migration ITs and fork-capture ITs that construct the engine directly.
     *
     * @param pool                 the connection pool the runner transacts on
     * @param registry             the workflow registry
     * @param instances            the instance repository
     * @param history              the history repository
     * @param dedup                the dedup repository
     * @param recorders            the side-effect recorders contributed by the test
     * @param optionalIntentKinds  the intent kinds the recorder router treats as optional
     * @param timerStore           the timer store
     * @param taskStore            the task store
     * @param clock                the clock
     * @param branchTokens         the branch token repository
     * @param joinStates           the join state repository
     * @param propagator           the durable-context propagator; may be {@code null}
     * @param migrationRegistryOpt the optional migration registry
     * @return a fully wired {@link WorkflowEngineHandle}
     */
    static WorkflowEngineHandle create(
            Pool pool,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            DurableContextPropagator propagator,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt) {
        return startedEngine(
                pool,
                propagator,
                WorkflowEngineParts.builder()
                        .txRunner(runnerFor(pool))
                        .registry(registry)
                        .instances(instances)
                        .history(history)
                        .dedup(dedup)
                        .branchTokens(standIn(branchTokens, BranchTokenRepository.class))
                        .joinStates(standIn(joinStates, JoinStateRepository.class))
                        .timerStore(timerStore)
                        .taskStore(taskStore)
                        .clock(clock)
                        .recorders(recorders)
                        .optionalIntentKinds(optionalIntentKinds)
                        .migrationRegistry(migrationRegistryOpt)
                        .propagator(propagator)
                        .typeNames(typeNamesOf(registry))
                        .definitionStore(InMemoryWorkflowDefinitionRegistryStore.of(registry)));
    }

    /**
     * Builds an engine like the overload with the migration registry, additionally taking the optional
     * {@link BeanValidator} that switches payload constraint enforcement on. An empty optional keeps
     * enforcement off. No inbound context initializers are registered.
     *
     * @param pool                 the connection pool the runner transacts on
     * @param registry             the workflow registry
     * @param instances            the instance repository
     * @param history              the history repository
     * @param dedup                the dedup repository
     * @param recorders            the side-effect recorders contributed by the test
     * @param optionalIntentKinds  the intent kinds the recorder router treats as optional
     * @param timerStore           the timer store
     * @param taskStore            the task store
     * @param clock                the clock
     * @param branchTokens         the branch token repository
     * @param joinStates           the join state repository
     * @param propagator           the durable-context propagator; may be {@code null}
     * @param migrationRegistryOpt the optional migration registry
     * @param beanValidator        the optional bean validator enforcing payload constraints
     * @return a fully wired {@link WorkflowEngineHandle}
     */
    static WorkflowEngineHandle create(
            Pool pool,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            DurableContextPropagator propagator,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt,
            Optional<BeanValidator> beanValidator) {
        return startedEngine(
                pool,
                propagator,
                WorkflowEngineParts.builder()
                        .txRunner(runnerFor(pool))
                        .registry(registry)
                        .instances(instances)
                        .history(history)
                        .dedup(dedup)
                        .branchTokens(standIn(branchTokens, BranchTokenRepository.class))
                        .joinStates(standIn(joinStates, JoinStateRepository.class))
                        .timerStore(timerStore)
                        .taskStore(taskStore)
                        .clock(clock)
                        .recorders(recorders)
                        .optionalIntentKinds(optionalIntentKinds)
                        .migrationRegistry(migrationRegistryOpt)
                        .propagator(propagator)
                        .inboundContextInitializers(Set.of())
                        .beanValidator(beanValidator)
                        .typeNames(typeNamesOf(registry))
                        .definitionStore(InMemoryWorkflowDefinitionRegistryStore.of(registry)));
    }

    /**
     * Builds an engine that compares state types through {@code stateCompatibility} when it migrates an instance.
     * Recorders are empty, workflow events are the only intent kind, and branch and join repositories are absent.
     */
    static WorkflowEngineHandle createWithStateCompatibility(
            Pool pool,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            WorkflowMigrationRegistry migrationRegistry,
            Optional<WorkflowStateCompatibility> stateCompatibility) {
        return startedEngine(
                pool,
                null,
                WorkflowEngineParts.builder()
                        .txRunner(runnerFor(pool))
                        .registry(registry)
                        .instances(instances)
                        .history(history)
                        .dedup(dedup)
                        .branchTokens(standIn(null, BranchTokenRepository.class))
                        .joinStates(standIn(null, JoinStateRepository.class))
                        .timerStore(timerStore)
                        .taskStore(taskStore)
                        .clock(clock)
                        .recorders(Set.of())
                        .optionalIntentKinds(Set.of(IntentKind.WORKFLOW_EVENT))
                        .migrationRegistry(Optional.of(migrationRegistry))
                        .propagator(null)
                        .inboundContextInitializers(Set.of())
                        .beanValidator(Optional.empty())
                        .typeNames(((DefaultWorkflowRegistry) registry).typeNames())
                        .stateCompatibility(stateCompatibility)
                        .definitionStore(InMemoryWorkflowDefinitionRegistryStore.of(registry)));
    }

    /**
     * The repository the caller supplied, or a stand-in that answers every read with nothing, so a test that never
     * forks or joins does not have to build the fork and join repositories the engine requires.
     */
    private static <T> T standIn(T supplied, Class<?> type) {
        if (supplied != null) return supplied;
        @SuppressWarnings("unchecked")
        T standIn = (T) Mockito.mock(type, PgWorkflowEngineTestSupport::answerNothing);
        return standIn;
    }

    /** Answers a call that returns a future with a completed future that holds the empty value of its result. */
    private static Object answerNothing(InvocationOnMock invocation) throws Throwable {
        if (!Future.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        }
        Type result = invocation.getMethod().getGenericReturnType() instanceof ParameterizedType parameterized
                ? parameterized.getActualTypeArguments()[0]
                : Object.class;
        Class<?> raw = result instanceof ParameterizedType nested ? (Class<?>) nested.getRawType() : (Class<?>) result;
        if (List.class.isAssignableFrom(raw)) return Future.succeededFuture(List.of());
        if (Optional.class.isAssignableFrom(raw)) return Future.succeededFuture(Optional.empty());
        if (raw == Integer.class) return Future.succeededFuture(0);
        if (raw == Long.class) return Future.succeededFuture(0L);
        if (raw == Boolean.class) return Future.succeededFuture(false);
        return Future.succeededFuture();
    }

    /** The type-name index of a default registry, and an empty index for any other registry. */
    private static WorkflowTypeNames typeNamesOf(WorkflowRegistry registry) {
        return registry instanceof DefaultWorkflowRegistry defaultRegistry
                ? defaultRegistry.typeNames()
                : new DefaultWorkflowTypeNames();
    }

    // --- dialect-specific runner construction (stays in postgresql) ---

    /**
     * Builds a {@link WorkflowTransactionRunner} over the given pool, mirroring the production wiring
     * (stage-1 {@link PgWorkflowExceptionMapper} + stage-2
     * {@link dev.vertique.workflow.engine.WorkflowExceptionMapper}) so the layered exception mapping
     * is exercised in tests that construct the engine directly.
     *
     * @param pool the connection pool the runner transacts on
     * @return a runner bound to {@code pool}
     */
    private static WorkflowTransactionRunner<SqlClient> runnerFor(Pool pool) {
        return new PgWorkflowTransactionRunner(
                new WorkflowTxRunnerRepository(pool, new PgWorkflowExceptionMapper()),
                new dev.vertique.workflow.engine.WorkflowExceptionMapper());
    }

    // --- executors of the plain overloads ---

    /**
     * Assembles an engine and starts its executor, which polls fast, unless the pool is a mock. The executor runs only
     * the starts that this engine recorded (and the completions, retries and migrations it recorded for any instance), as
     * the engine ran them itself before starts were recorded: tests build
     * several engines over one database with different registries, and an executor that ran another engine's start would
     * run it with the wrong plan. The executor stops itself once the engine's pool is closed, so a test that closes its
     * pool at the end leaves nothing running.
     */
    private static WorkflowEngineHandle startedEngine(
            Pool pool, DurableContextPropagator propagator, WorkflowEngineParts.Builder parts) {
        AtomicReference<WorkflowEngineHandle> handle = new AtomicReference<>();
        java.util.Set<java.util.UUID> own = java.util.concurrent.ConcurrentHashMap.newKeySet();
        WorkflowCommandRepository<SqlClient> commands = ownStartsOnly(
                new PgWorkflowCommandRepository(
                        pool,
                        new PgDbExceptionMapper(),
                        DbSchemas.empty(),
                        WorkflowRecordingConfig.defaults(),
                        propagator),
                own,
                handle);
        boolean mocked = Mockito.mockingDetails(pool).isMock();
        WorkflowEngineHandle created = WorkflowEngineFactory.create(parts.commands(commands)
                .taskReservations(new PgTaskReservations(pool, new PgDbExceptionMapper()))
                .vertx(mocked ? null : ownerOf(pool))
                .executorConfig(new WorkflowExecutorConfig(1, 8, 100, 60))
                .build());
        if (mocked) {
            // an engine over a mocked pool has no database to claim from, so it runs no executor
            handle.set(created);
            return created;
        }
        WorkflowEngineHandle engine = WorkflowTestTables.tracking(created, pool);
        handle.set(engine);
        try {
            engine.executor().start().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The executor of the test engine did not start", e);
        }
        return remembering(engine, own);
    }

    /** Wraps an engine so that every instance it records a start for is remembered in {@code own}. */
    private static WorkflowEngineHandle remembering(WorkflowEngineHandle engine, java.util.Set<java.util.UUID> own) {
        return (WorkflowEngineHandle) Proxy.newProxyInstance(
                WorkflowEngineHandle.class.getClassLoader(),
                new Class<?>[] {WorkflowEngineHandle.class},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(engine, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("start".equals(method.getName()) && result instanceof Future<?> future) {
                        future.onSuccess(id -> {
                            if (id instanceof dev.vertique.workflow.ops.WorkflowInstanceId started) {
                                own.add(started.value());
                            }
                        });
                    }
                    return result;
                });
    }

    /**
     * Decorates a command repository so that a peek returns only the heads of instances in {@code own} (the instances
     * whose start or task completion this engine recorded), and so that a peek that fails because the pool was closed
     * stops the executor.
     */
    @SuppressWarnings("unchecked")
    private static WorkflowCommandRepository<SqlClient> ownStartsOnly(
            WorkflowCommandRepository<SqlClient> real,
            java.util.Set<java.util.UUID> own,
            AtomicReference<WorkflowEngineHandle> handle) {
        java.util.Set<Long> recoveries = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                WorkflowCommandRepository.class.getClassLoader(),
                new Class<?>[] {WorkflowCommandRepository.class},
                (proxy, method, arguments) -> {
                    if (("recordCompletion".equals(method.getName())
                                    || "recordRetry".equals(method.getName())
                                    || "recordMigration".equals(method.getName()))
                            && arguments[0] instanceof dev.vertique.workflow.ops.WorkflowInstanceId recorded) {
                        // a completion, retry or migration this engine records is run by this engine, whoever started
                        // the instance
                        own.add(recorded.value());
                    }
                    boolean recovery =
                            "recordRetry".equals(method.getName()) || "recordMigration".equals(method.getName());
                    boolean peek = "peek".equals(method.getName());
                    Object[] forwarded = arguments;
                    int limit = 0;
                    if (peek) {
                        limit = (Integer) arguments[0];
                        forwarded = new Object[] {Math.max(limit, 256)};
                    }
                    Object result;
                    try {
                        result = method.invoke(real, forwarded);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (recovery && result instanceof Future<?> recorded) {
                        // only the engine that recorded a retry or a migration runs it: another engine over the same
                        // instance may hold other plans and would defer it
                        recorded.onSuccess(commandId -> recoveries.add((Long) commandId));
                    }
                    if (!peek || !(result instanceof Future<?> future)) {
                        return result;
                    }
                    int wanted = limit;
                    return ((Future<List<dev.vertique.workflow.engine.spi.CommandHead>>) future)
                            .<List<dev.vertique.workflow.engine.spi.CommandHead>>map(heads -> heads.stream()
                                    .filter(head -> own.contains(
                                                    head.workflowId().value())
                                            && (!("RETRY".equals(head.kind()) || "MIGRATION".equals(head.kind()))
                                                    || recoveries.contains(head.commandId())))
                                    .limit(wanted)
                                    .toList())
                            .recover(failure -> {
                                if (!poolClosed(failure)) {
                                    return Future.failedFuture(failure);
                                }
                                handle.get().executor().stop();
                                return Future.succeededFuture(List.of());
                            });
                });
    }

    private static boolean poolClosed(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (t instanceof java.util.concurrent.RejectedExecutionException
                    || message != null
                            && (message.toLowerCase(java.util.Locale.ROOT).contains("closed")
                                    || message.toLowerCase(java.util.Locale.ROOT)
                                            .contains("terminated"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Vert.x instance a pool runs on. The executor must run on it: a segment's continuations return on the pool's
     * Vert.x instance, so a verticle of another instance would lose its duplicated context after the first statement.
     */
    private static Vertx ownerOf(Pool pool) {
        java.util.concurrent.CompletableFuture<Vertx> owner = new java.util.concurrent.CompletableFuture<>();
        pool.query("SELECT 1")
                .execute()
                .onComplete(result -> owner.complete(Vertx.currentContext().owner()));
        try {
            return owner.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not find the Vert.x instance of the pool", e);
        }
    }

    // --- engines with a controllable executor ---

    /**
     * Starts the description of an engine whose executor a test starts and stops itself, over real PostgreSQL
     * repositories on the given pool. The engine runs its executor on the given Vert.x instance.
     *
     * @param pool     the connection pool the engine transacts on
     * @param vertx    the Vert.x instance the executor is deployed on
     * @param registry the workflow registry
     * @return the description, with the defaults of a plain engine
     */
    public static ExecutorEngine executorEngine(Pool pool, Vertx vertx, WorkflowRegistry registry) {
        return new ExecutorEngine(pool, vertx, registry);
    }

    /**
     * Describes an engine with a controllable executor: the executor configuration, the recorders, the durable
     * context propagator, and decorators for the transaction runner and the command repository the engine is
     * built with. The decorators are applied to the runner and the repository this support type builds, so no
     * production visibility changes for them.
     */
    public static final class ExecutorEngine {

        private final Pool pool;
        private final Vertx vertx;
        private final WorkflowRegistry registry;
        private Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of();
        private WorkflowExecutorConfig executorConfig = WorkflowExecutorConfig.defaults();
        private WorkflowRecordingConfig recordingConfig;
        private WorkflowDefinitionRegistryStore definitionStore;
        private WorkflowPlanSchemaProducer schemaProducer;
        private WorkflowMigrationRegistry migrationRegistry;
        private WorkflowHistoryRedactor historyRedactor;
        private DurableContextPropagator propagator;
        private Set<InboundContextInitializer> inboundContextInitializers = Set.of();
        private Set<WorkflowExecutorObserver> executorObservers = Set.of();
        private CommandWakeups commandWakeups = CommandWakeups.none();
        private Set<WorkflowHistoryListener> historyListeners = Set.of();
        private UnaryOperator<WorkflowTransactionRunner<SqlClient>> runnerDecorator = UnaryOperator.identity();
        private UnaryOperator<WorkflowCommandRepository<SqlClient>> commandsDecorator = UnaryOperator.identity();

        private ExecutorEngine(Pool pool, Vertx vertx, WorkflowRegistry registry) {
            this.pool = pool;
            this.vertx = vertx;
            this.registry = registry;
        }

        /** The side-effect recorders of the engine. */
        public ExecutorEngine recorders(Set<WorkflowSideEffectRecorder<SqlClient>> recorders) {
            this.recorders = recorders;
            return this;
        }

        /** The executor configuration: instances, concurrency, poll interval and lease. */
        public ExecutorEngine executorConfig(WorkflowExecutorConfig executorConfig) {
            this.executorConfig = executorConfig;
            return this;
        }

        /** The recording bounds, when a proof overrides the engine defaults. */
        public ExecutorEngine recordingConfig(WorkflowRecordingConfig recordingConfig) {
            this.recordingConfig = recordingConfig;
            return this;
        }

        /** The producer of the decision schemas new tasks store in their snapshots; none by default. */
        public ExecutorEngine schemaProducer(WorkflowPlanSchemaProducer schemaProducer) {
            this.schemaProducer = schemaProducer;
            return this;
        }

        /** The migration handlers the engine runs; none by default. */
        public ExecutorEngine migrationRegistry(WorkflowMigrationRegistry migrationRegistry) {
            this.migrationRegistry = migrationRegistry;
            return this;
        }

        /** The history redactor the application binds; none by default, so nothing is redacted. */
        public ExecutorEngine historyRedactor(WorkflowHistoryRedactor historyRedactor) {
            this.historyRedactor = historyRedactor;
            return this;
        }

        /** The durable definition rows used by recording, in place of the in-memory default. */
        public ExecutorEngine definitionStore(WorkflowDefinitionRegistryStore definitionStore) {
            this.definitionStore = definitionStore;
            return this;
        }

        /** The observers the engine's executor reports its events to. */
        public ExecutorEngine executorObservers(Set<WorkflowExecutorObserver> executorObservers) {
            this.executorObservers = executorObservers;
            return this;
        }

        /** The wake-up source the engine's executor instances register with; none by default. */
        public ExecutorEngine commandWakeups(CommandWakeups commandWakeups) {
            this.commandWakeups = commandWakeups;
            return this;
        }

        /** The history listeners the engine announces committed history and failed actions to; none by default. */
        public ExecutorEngine historyListeners(Set<WorkflowHistoryListener> historyListeners) {
            this.historyListeners = historyListeners;
            return this;
        }

        /** The durable context propagator the engine captures and binds with. */
        public ExecutorEngine propagator(DurableContextPropagator propagator) {
            this.propagator = propagator;
            return this;
        }

        /** The first-ingress initializers that run after every durable bind; none by default. */
        public ExecutorEngine inboundContextInitializers(Set<InboundContextInitializer> initializers) {
            this.inboundContextInitializers = initializers;
            return this;
        }

        /** Wraps the transaction runner that {@code runnerFor(Pool)} builds. */
        public ExecutorEngine runnerDecorator(UnaryOperator<WorkflowTransactionRunner<SqlClient>> decorator) {
            this.runnerDecorator = decorator;
            return this;
        }

        /** Wraps the command repository the engine is built with. */
        public ExecutorEngine commandsDecorator(UnaryOperator<WorkflowCommandRepository<SqlClient>> decorator) {
            this.commandsDecorator = decorator;
            return this;
        }

        /**
         * Builds the engine. Its executor is not started; the test starts and stops it through
         * {@link WorkflowEngineHandle#executor()}.
         *
         * @return the engine handle
         */
        public WorkflowEngineHandle build() {
            PgDbExceptionMapper ex = new PgDbExceptionMapper();
            PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
            WorkflowHistoryEventPublisher[] publisher = {WorkflowHistoryEventPublisher.disabled()};
            WorkflowCommandRepository<SqlClient> commands = commandsDecorator.apply(new PgWorkflowCommandRepository(
                    pool, ex, DbSchemas.empty(), WorkflowRecordingConfig.defaults(), propagator, () -> publisher[0]));
            if (!historyListeners.isEmpty()) {
                publisher[0] = new WorkflowHistoryEventPublisher(
                        historyListeners, history, pool, commands, CommandActors.ambient());
            }
            WorkflowEngineParts.Builder parts = WorkflowEngineParts.builder()
                    .txRunner(runnerDecorator.apply(runnerFor(pool)))
                    .commands(commands)
                    .historyPublisher(publisher[0])
                    .registry(registry)
                    .instances(new PgWorkflowInstanceRepository(pool, ex))
                    .history(history)
                    .dedup(new PgWorkflowDedupRepository(pool, ex))
                    .branchTokens(new PgBranchTokenRepository(pool, ex))
                    .joinStates(new PgJoinStateRepository(pool, ex))
                    .timerStore(new PgTimerStore(ex))
                    .taskStore(new PgTaskStore(pool, ex))
                    .taskReservations(new PgTaskReservations(pool, ex))
                    .clock(Clock.systemUTC())
                    .recorders(recorders)
                    .optionalIntentKinds(Set.of(IntentKind.WORKFLOW_EVENT))
                    .propagator(propagator)
                    .inboundContextInitializers(inboundContextInitializers)
                    .typeNames(typeNamesOf(registry))
                    .definitionStore(
                            definitionStore != null
                                    ? definitionStore
                                    : InMemoryWorkflowDefinitionRegistryStore.of(registry))
                    .vertx(vertx)
                    .executorConfig(executorConfig)
                    .executorObservers(executorObservers)
                    .commandWakeups(commandWakeups);
            if (recordingConfig != null) {
                parts.recordingConfig(recordingConfig);
            }
            if (schemaProducer != null) {
                parts.schemaProducer(Optional.of(schemaProducer));
            }
            if (migrationRegistry != null) {
                parts.migrationRegistry(Optional.of(migrationRegistry));
            }
            if (historyRedactor != null) {
                parts.historyRedactor(Optional.of(historyRedactor));
            }
            return WorkflowTestTables.tracking(WorkflowEngineFactory.create(parts.build()), pool);
        }
    }
}
