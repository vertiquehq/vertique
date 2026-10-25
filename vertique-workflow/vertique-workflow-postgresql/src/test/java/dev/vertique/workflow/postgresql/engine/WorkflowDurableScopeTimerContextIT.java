// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.DelayedJob;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.workflow.delayed.compose.WorkflowDelayedComposeValidator;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryConfig;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryService;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves persisted timer capture, recovery callbacks, orphan re-enqueue, and async scope cleanup. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowDurableScopeTimerContextIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_scope")
            .withMigration("classpath:db/migration/workflow");
    private static final Instant NOW = Instant.parse("2026-01-02T12:00:00Z");
    private static final ScopeContext ACME = new ScopeContext(new ScopeKey("acme"));
    private static final ScopeContext PREVIOUS = new ScopeContext(new ScopeKey("previous"));
    private static Vertx vertx;
    private static Pool pool;
    private static TimerGraph graph;
    private static final Probe probe = new Probe();

    record State(String key) {}

    interface Contract {}

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
                    .toStep("done")
                    .complete("done");
            wf.initialStep("wait");
        }
    }

    static final class Probe {
        final List<Optional<ScopeContext>> enqueuedScopes = new ArrayList<>();
        final List<DurableMetadata> enqueuedMetadata = new ArrayList<>();
        final List<Optional<ScopeContext>> callbackScopes = new ArrayList<>();
        boolean orphan;
        boolean asynchronousCallback;
        boolean failCallback;
        boolean callbackSettled;
        boolean callbackFailed;

        void reset() {
            enqueuedScopes.clear();
            enqueuedMetadata.clear();
            callbackScopes.clear();
            orphan = true;
            asynchronousCallback = false;
            failCallback = false;
            callbackSettled = false;
            callbackFailed = false;
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
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(java.util.Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper mapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        static WorkflowDelayedComposeValidator delayedValidator() {
            return mock(WorkflowDelayedComposeValidator.class);
        }

        @Provides
        static WorkflowTimerRecoveryConfig recoveryConfig() {
            return new WorkflowTimerRecoveryConfig(10, Duration.ofSeconds(30));
        }

        @Provides
        @Singleton
        static DelayedJobHandlerRegistrar handlerRegistrar() {
            DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
            when(registrar.handlerAddresses()).thenReturn(Map.of("timer-scope", "test.scope.timer-fire"));
            return registrar;
        }

        @Provides
        @Singleton
        static PgJobRepository jobRows(Probe probe) {
            PgJobRepository repository = mock(PgJobRepository.class);
            when(repository.save(any(JobExecution.class), any(SqlClient.class))).thenAnswer(invocation -> {
                JobExecution execution = invocation.getArgument(0);
                probe.enqueuedMetadata.add(execution.metadata());
                return Future.succeededFuture(execution.id());
            });
            return repository;
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> timerRecorder(WorkflowTimerSideEffectRecorder recorder) {
            return recorder;
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> events(ContextHolder holder, Probe probe) {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.WORKFLOW_EVENT;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    if (!"WORKFLOW_FAILED".equals(intent.targetId())) {
                        return Future.succeededFuture(RecorderResult.empty());
                    }
                    probe.callbackScopes.add(holder.current(ScopeContext.class));
                    Promise<RecorderResult> callback = Promise.promise();
                    Runnable completion = () -> {
                        if (probe.failCallback) {
                            callback.fail(new IllegalStateException("async timer callback failure"));
                        } else {
                            callback.complete(RecorderResult.empty());
                        }
                    };
                    if (probe.asynchronousCallback) {
                        Vertx.currentContext().runOnContext(ignored -> completion.run());
                    } else {
                        completion.run();
                    }
                    return callback.future().transform(result -> {
                        probe.callbackSettled = true;
                        probe.callbackFailed = result.failed();
                        return result.succeeded()
                                ? Future.succeededFuture(result.result())
                                : Future.failedFuture(result.cause());
                    });
                }
            };
        }
    }

    @Singleton
    @Component(modules = {WorkflowPostgresqlModule.class, ContextRuntimeModule.class, FixtureModule.class})
    interface TimerGraph {
        WorkflowOperations operations();

        WorkflowTimerRecoveryService recovery();

        DelayedJobService delayedJobs();

        ContextHolder holder();

        WorkflowRegistry registry();

        PgWorkflowDefinitionRegistryStore definitionStore();

        WorkflowDefinitionLifecycle definitionLifecycle();

        @Component.Factory
        interface Factory {
            TimerGraph create(
                    @BindsInstance Pool pool,
                    @BindsInstance Clock clock,
                    @BindsInstance Probe probe,
                    @BindsInstance JobRepository jobs,
                    @BindsInstance WorkflowTimerFireJob fireJob);
        }
    }

    @BeforeAll
    static void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findById(any()))
                .thenAnswer(invocation -> Future.succeededFuture(
                        probe.orphan ? Optional.empty() : Optional.of(deadLetter(invocation.getArgument(0)))));
        WorkflowTimerFireJob fireJob = mock(WorkflowTimerFireJob.class);
        when(fireJob.enqueue(any(), any(DelayedJobOptions.class), any(SqlClient.class)))
                .thenAnswer(invocation -> {
                    probe.enqueuedScopes.add(graph.holder().current(ScopeContext.class));
                    DelayedJobOptions options = invocation.getArgument(1);
                    return graph.delayedJobs()
                            .enqueue(
                                    DelayedJob.builder()
                                            .handler("timer-scope")
                                            .payload(invocation.getArgument(0))
                                            .runAt(options.runAt())
                                            .build(),
                                    invocation.getArgument(2));
                });
        graph = DaggerWorkflowDurableScopeTimerContextIT_TimerGraph.factory()
                .create(pool, Clock.fixed(NOW, ZoneOffset.UTC), probe, jobs, fireJob);
        DefaultVersionSeed.recordAndActivate(graph.definitionStore(), graph.definitionLifecycle(), graph.registry());
    }

    @BeforeEach
    void reset() throws Exception {
        await(pool.query("TRUNCATE workflow_branch_tokens, workflow_join_states, workflow_timers, workflow_history,"
                        + " workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute());
        probe.reset();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pool != null) {
            await(pool.close());
        }
    }

    @Test
    @DisplayName(
            "a timer persists captured scope, restores it for orphan re-enqueue, and exposes it to recovery callbacks")
    void restoresScopeAfterTimerRecoveryAndReenqueue(VertxTestContext ctx) {
        startTimer(ACME)
                .compose(id -> timerRow(id).map(row -> {
                    assertEquals(
                            savedScope().toCarrier(),
                            row.getJsonObject("metadata"),
                            "the production timer recorder must persist the exact captured scope");
                    return id;
                }))
                .compose(id -> makeRecoverable(id, null).map(id))
                .compose(id -> {
                    probe.reset();
                    return onFreshDuplicate(() -> graph.recovery().reconcile()).map(id);
                })
                .compose(id -> {
                    assertEquals(
                            List.of(Optional.of(ACME)),
                            probe.enqueuedScopes,
                            "orphan re-enqueue must observe the restored timer scope");
                    assertEquals(
                            savedScope().toJson(),
                            probe.enqueuedMetadata.getFirst().toJson(),
                            "re-enqueue must capture scope from its callback context");
                    probe.orphan = false;
                    return onFreshDuplicate(() -> graph.recovery().reconcile());
                })
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertEquals(
                            List.of(Optional.of(ACME)),
                            probe.callbackScopes,
                            "the terminal timer callback must observe persisted acme");
                    assertTrue(probe.callbackSettled);
                    assertFalse(probe.callbackFailed);
                    ctx.completeNow();
                })));
    }

    record TerminalObservation(
            boolean failing,
            Optional<ScopeContext> installed,
            Optional<ScopeContext> restored,
            boolean settled,
            boolean failed) {}

    @Test
    @DisplayName("timer success and asynchronous failure both unwind an installed saved scope to the previous binding")
    void restoresPreviousBindingAfterTimerSuccessAndFailure(VertxTestContext ctx) {
        List<TerminalObservation> outcomes = new ArrayList<>();
        terminalScenario(false)
                .compose(success -> {
                    outcomes.add(success);
                    return terminalScenario(true);
                })
                .onComplete(ctx.succeeding(failure -> ctx.verify(() -> {
                    outcomes.add(failure);
                    assertAll(outcomes.stream().map(outcome -> (org.junit.jupiter.api.function.Executable) () -> {
                        assertEquals(
                                Optional.of(ACME),
                                outcome.installed(),
                                "saved scope must be installed inside the timer callback before "
                                        + (outcome.failing() ? "failure" : "success"));
                        assertEquals(
                                Optional.of(PREVIOUS),
                                outcome.restored(),
                                "timer completion must restore the previous holder value");
                        assertTrue(
                                outcome.settled(), "the asynchronous callback must settle before cleanup is credited");
                        assertEquals(outcome.failing(), outcome.failed());
                    }));
                    ctx.completeNow();
                })));
    }

    private static Future<TerminalObservation> terminalScenario(boolean failing) {
        return pool.query("TRUNCATE workflow_timers, workflow_history, workflow_dedup, workflow_instances CASCADE")
                .execute()
                .compose(ignored -> startTimer(null))
                .compose(id -> makeRecoverable(id, savedScope()).map(id))
                .compose(id -> {
                    probe.reset();
                    probe.orphan = false;
                    probe.asynchronousCallback = true;
                    probe.failCallback = failing;
                    return onFreshDuplicate(() -> {
                        ContextHolder.Scope previous = graph.holder().bind(ScopeContext.class, PREVIOUS);
                        try {
                            return graph.recovery()
                                    .reconcile()
                                    .map(ignored -> new TerminalObservation(
                                            failing,
                                            probe.callbackScopes.getFirst(),
                                            graph.holder().current(ScopeContext.class),
                                            probe.callbackSettled,
                                            probe.callbackFailed))
                                    .eventually(() -> {
                                        previous.close();
                                        return Future.succeededFuture();
                                    });
                        } catch (Throwable failure) {
                            previous.close();
                            return Future.failedFuture(failure);
                        }
                    });
                });
    }

    private static Future<WorkflowInstanceId> startTimer(ScopeContext scope) {
        StartCommand command = new StartCommand(
                "durable-scope-timer",
                Contract.class.getName(),
                new State("timer"),
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);
        return onFreshDuplicate(() -> {
            ContextHolder.Scope binding =
                    scope == null ? () -> {} : graph.holder().bind(ScopeContext.class, scope);
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

    private static Future<Row> timerRow(WorkflowInstanceId id) {
        return pool.preparedQuery(
                        "SELECT metadata, delayed_job_execution_id FROM workflow_timers WHERE workflow_id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next());
    }

    private static Future<Void> makeRecoverable(WorkflowInstanceId id, DurableMetadata seededMetadata) {
        OffsetDateTime overdue = OffsetDateTime.ofInstant(NOW.minusSeconds(120), ZoneOffset.UTC);
        if (seededMetadata == null) {
            return pool.preparedQuery("UPDATE workflow_timers SET fire_at = $1 WHERE workflow_id = $2")
                    .execute(Tuple.of(overdue, id.value()))
                    .mapEmpty();
        }
        return pool.preparedQuery("UPDATE workflow_timers SET fire_at = $1, metadata = $2 WHERE workflow_id = $3")
                .execute(Tuple.of(overdue, seededMetadata.toCarrier(), id.value()))
                .mapEmpty();
    }

    private static DurableMetadata savedScope() {
        return DurableMetadata.of(
                "scope", new JsonObject().put("schemaVersion", 1).put("scopeKey", "acme"));
    }

    private static JobExecution deadLetter(UUID id) {
        return new JobExecution(
                id,
                "timer",
                JobType.DELAYED,
                "timer-handler",
                "default",
                JobState.DEAD_LETTER,
                3,
                3,
                null,
                0,
                null,
                NOW,
                NOW,
                NOW,
                NOW,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
