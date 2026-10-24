// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.workflow.engine.WorkflowEngineFactory;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.WorkflowEngineParts;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
final class PgWorkflowEngineTestSupport {

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
        return WorkflowEngineFactory.create(WorkflowEngineParts.builder()
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
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
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
        return WorkflowEngineFactory.create(WorkflowEngineParts.builder()
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
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
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
        return WorkflowEngineFactory.create(WorkflowEngineParts.builder()
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
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
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
}
