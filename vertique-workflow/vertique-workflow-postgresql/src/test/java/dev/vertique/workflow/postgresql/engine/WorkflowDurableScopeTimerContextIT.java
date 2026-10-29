// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that a recorded timer persists the durable scope captured when it was scheduled, and that the executor that
 * fires the timer command runs the timer's step under exactly that scope and leaves nothing bound afterwards, whether
 * the step succeeds or fails asynchronously.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowDurableScopeTimerContextIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_scope")
            .withMigration("classpath:db/migration/workflow");
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(20);
    private static final ScopeContext ACME = scope("acme");
    private static final String FAIL_KEY_PREFIX = "fail-";
    private static Vertx vertx;
    private static Pool pool;
    private static TimerGraph graph;
    private static ExecutorDeployment executor;
    private static final Probe probe = new Probe();

    record State(String key) {}

    interface Contract {}

    /** The scope that was bound while the timer's step ran, by instance. */
    record Observation(WorkflowInstanceId instance, Optional<ScopeContext> scope) {}

    static final class Probe {
        final List<Observation> steps = new CopyOnWriteArrayList<>();

        void reset() {
            steps.clear();
        }

        List<Optional<ScopeContext>> scopesOf(WorkflowInstanceId id) {
            return steps.stream()
                    .filter(observation -> observation.instance().equals(id))
                    .map(Observation::scope)
                    .toList();
        }
    }

    static final class Definition implements WorkflowDefinition<State, Contract> {
        @Override
        public Class<Contract> contract() {
            return Contract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "durable-scope-timer";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, state -> state)
                    .timer("wait", Duration.ofSeconds(1))
                    .toStep("dispatch")
                    .dispatch("dispatch", "scope.service", State::key, "done")
                    .complete("done");
            wf.initialStep("wait");
        }
    }

    @Module
    static class FixtureModule {
        @Provides
        @IntoSet
        static WorkflowContributor definition() {
            return registry -> registry.register(new Definition());
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator validator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper mapper() {
            return new PgDbExceptionMapper();
        }

        /** Observes the scope bound while the step after the timer runs, and fails it asynchronously on request. */
        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> stepRecorder(ContextHolder holder, Probe probe) {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    probe.steps.add(
                            new Observation(intent.correlation().workflowId(), holder.current(ScopeContext.class)));
                    if (!String.valueOf(intent.payload()).startsWith(FAIL_KEY_PREFIX)) {
                        return Future.succeededFuture(RecorderResult.empty());
                    }
                    Promise<RecorderResult> failed = Promise.promise();
                    Vertx.currentContext()
                            .runOnContext(
                                    ignored -> failed.fail(new IllegalStateException("async timer step failure")));
                    return failed.future();
                }
            };
        }
    }

    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                ContextRuntimeModule.class,
                ExecutorDeployment.FastPolling.class,
                FixtureModule.class
            })
    interface TimerGraph {
        Set<VerticleDeployment> verticleDeployments();

        WorkflowOperations operations();

        WorkflowExecutionProbe executionProbe();

        ContextHolder holder();

        WorkflowRegistry registry();

        PgWorkflowDefinitionRegistryStore definitionStore();

        WorkflowDefinitionLifecycle definitionLifecycle();

        @Component.Factory
        interface Factory {
            TimerGraph create(@BindsInstance Pool pool, @BindsInstance Clock clock, @BindsInstance Probe probe);
        }
    }

    @BeforeAll
    static void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(6))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        graph = DaggerWorkflowDurableScopeTimerContextIT_TimerGraph.factory().create(pool, Clock.systemUTC(), probe);
        // The graph runs no deployer: only its workflow executor is deployed
        executor = await(ExecutorDeployment.deploy(vertx, graph.verticleDeployments()));
        DefaultVersionSeed.recordAndActivate(graph.definitionStore(), graph.definitionLifecycle(), graph.registry());
    }

    @BeforeEach
    void reset() {
        await(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE workflow_branch_tokens, workflow_join_states, workflow_timers, workflow_tasks,"
                                + " workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute());
        probe.reset();
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            await(executor.undeploy());
        }
        if (pool != null) {
            await(pool.close());
        }
    }

    @Test
    @DisplayName("a timer persists the scope captured when it was scheduled and its step runs under that scope")
    void timerPersistsScheduledScopeAndRunsItsStepUnderIt() {
        // Given a workflow started under the acme scope, whose executor-scheduled timer is waiting
        WorkflowInstanceId id = await(start("timer", ACME));
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        UUID timerId = await(TimerCommandRows.onlyTimerId(pool, id, TimerPurpose.STANDALONE));

        // Then the timer row holds exactly the captured scope
        assertThat(timerMetadata(timerId))
                .as("the recorded timer must persist the exact captured scope")
                .isEqualTo(saved("acme").toCarrier());
        assertThat(probe.scopesOf(id))
                .as("the step must not run before the timer fires")
                .isEmpty();

        // When the timer command is due and the executor fires it
        await(TimerCommandRows.makeDue(pool, timerId));
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));

        // Then the step after the timer ran under the scope the timer captured, not under the executor's ambient one
        assertThat(probe.scopesOf(id)).containsExactly(Optional.of(ACME));
        assertThat(instanceStatus(id)).isEqualTo("COMPLETED");
        assertThat(await(TimerCommandRows.status(pool, timerId))).isEqualTo("FIRED");
    }

    @Test
    @DisplayName("a timer scheduled without a scope persists none and its step runs with none bound")
    void timerScheduledWithoutScopeRunsWithoutScope() {
        // Given a workflow started with no scope bound, whose timer is waiting
        WorkflowInstanceId id = await(start("timer", null));
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        UUID timerId = await(TimerCommandRows.onlyTimerId(pool, id, TimerPurpose.STANDALONE));
        assertThat(timerMetadata(timerId)).isNull();

        // When the timer fires
        await(TimerCommandRows.makeDue(pool, timerId));
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));

        // Then the step saw no scope
        assertThat(probe.scopesOf(id)).containsExactly(Optional.empty());
        assertThat(instanceStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a persisted timer scope is decoded by the durable codec and unwound after a step success and failure")
    void persistedTimerScopeIsDecodedAndUnwoundAfterSuccessAndAsynchronousFailure() {
        // Given a timer whose persisted scope is the scope-only document of saved-only, run to a successful step
        WorkflowInstanceId success = await(start("timer-success", null));
        fireWithPersistedScope(success, saved("saved-only"));

        // And a timer of the same shape whose step fails asynchronously
        WorkflowInstanceId failure = await(start(FAIL_KEY_PREFIX + "timer", null));
        fireWithPersistedScope(failure, saved("saved-only"));

        // Then both steps ran under the decoded persisted scope, and the failure reached the executor
        assertThat(probe.scopesOf(success)).containsExactly(Optional.of(scope("saved-only")));
        assertThat(probe.scopesOf(failure))
                .isNotEmpty()
                .allMatch(scope -> scope.equals(Optional.of(scope("saved-only"))));
        assertThat(instanceStatus(success)).isEqualTo("COMPLETED");
        assertThat(instanceStatus(failure)).isNotEqualTo("COMPLETED");

        // When timers without any persisted scope fire afterwards on the same executor
        WorkflowInstanceId afterSuccess = await(start("timer-after", null));
        fireWithPersistedScope(afterSuccess, null);

        // Then no scope of the earlier steps stays bound for them, neither after the success nor the failure
        assertThat(probe.scopesOf(afterSuccess)).containsExactly(Optional.empty());
    }

    private static void fireWithPersistedScope(WorkflowInstanceId id, DurableMetadata persisted) {
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        UUID timerId = await(TimerCommandRows.onlyTimerId(pool, id, TimerPurpose.STANDALONE));
        if (persisted != null) {
            await(pool.preparedQuery("UPDATE workflow_timers SET metadata = $1 WHERE timer_id = $2")
                    .execute(Tuple.of(persisted.toCarrier(), timerId)));
        }
        await(TimerCommandRows.makeDue(pool, timerId));
        await(graph.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
    }

    private static Future<WorkflowInstanceId> start(String key, ScopeContext ambient) {
        StartCommand command = new StartCommand(
                "durable-scope-timer",
                Contract.class.getName(),
                new State(key),
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);
        return onFreshDuplicate(() -> {
            ContextHolder.Scope binding =
                    ambient == null ? () -> {} : graph.holder().bind(ScopeContext.class, ambient);
            try {
                return graph.operations().start(command).eventually(() -> {
                    binding.close();
                    return Future.succeededFuture();
                });
            } catch (Throwable failure) {
                binding.close();
                return Future.failedFuture(failure);
            }
        });
    }

    private static JsonObject timerMetadata(UUID timerId) {
        return await(pool.preparedQuery("SELECT metadata FROM workflow_timers WHERE timer_id = $1")
                        .execute(Tuple.of(timerId)))
                .iterator()
                .next()
                .getJsonObject("metadata");
    }

    private static String instanceStatus(WorkflowInstanceId id) {
        return await(pool.preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getString("status");
    }

    private static ScopeContext scope(String key) {
        return new ScopeContext(new ScopeKey(key));
    }

    private static DurableMetadata saved(String key) {
        return DurableMetadata.of("scope", new JsonObject().put("scopeKey", key));
    }

    private static <T> Future<T> onFreshDuplicate(Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(ignored -> {
            try {
                action.get().onComplete(result);
            } catch (Throwable failure) {
                result.fail(failure);
            }
        });
        return result.future();
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete: " + e, e);
        }
    }
}
