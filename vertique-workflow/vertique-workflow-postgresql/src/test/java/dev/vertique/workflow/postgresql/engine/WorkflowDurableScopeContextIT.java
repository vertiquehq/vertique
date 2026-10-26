// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

/** Proves workflow persistence, caller precedence, bind gates, selection, and scope unwinding. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowDurableScopeContextIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_durable_scope")
            .withMigration("classpath:db/migration/workflow");
    private static final ScopeContext ACME = scope("acme");
    private static Pool pool;
    private static Vertx vertx;
    private static WorkflowGraph graph;
    private static final Probe probe = new Probe();

    record Start(String key) {}

    record State(String key) {}

    interface Contract {
        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedup);
    }

    record DecoderOnly(String value) implements ContextValue {}

    record Unregistered(String value) implements ContextValue {}

    record Initialized(String value) implements ContextValue {}

    record Observation(
            Optional<ScopeContext> scope,
            Optional<DecoderOnly> registered,
            Optional<Unregistered> unregistered,
            Optional<Initialized> initialized) {}

    static final class Probe {
        final List<Observation> initializers = new ArrayList<>();
        final List<Observation> services = new ArrayList<>();
        final List<WorkflowEventEnvelope> events = new ArrayList<>();
        int closedInitializers;
        boolean failService;

        void reset() {
            initializers.clear();
            services.clear();
            events.clear();
            closedInitializers = 0;
            failService = false;
        }

        Observation observe(ContextHolder holder) {
            return new Observation(
                    holder.current(ScopeContext.class),
                    holder.current(DecoderOnly.class),
                    holder.current(Unregistered.class),
                    holder.current(Initialized.class));
        }
    }

    static final class Definition implements WorkflowDefinition<State, Contract> {
        private final boolean branch;

        Definition(boolean branch) {
            this.branch = branch;
        }

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
            return branch ? "scope-branch" : "scope-instance";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.key()));
            if (branch) {
                wf.fork("fork")
                        .branch("a", "wait-a")
                        .join("join")
                        .waitForSignal("wait-a", "go", String.class)
                        .onSignal((state, signal) -> state)
                        .toStepOnSignal("dispatch-a")
                        .build()
                        .dispatch("dispatch-a", "scope.service", state -> state.key(), "complete-a")
                        .complete("complete-a")
                        .join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .endJoin()
                        .complete("done");
                wf.initialStep("fork");
            } else {
                wf.waitForSignal("wait", "go", String.class)
                        .onSignal((state, signal) -> state)
                        .toStepOnSignal("dispatch")
                        .build()
                        .dispatch("dispatch", "scope.service", state -> state.key(), "done")
                        .complete("done");
                wf.initialStep("wait");
            }
        }
    }

    @Module
    static class FixtureModule {
        @Provides
        @IntoSet
        static WorkflowContributor definitions() {
            return registry -> {
                registry.register(new Definition(false));
                registry.register(new Definition(true));
            };
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

        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> decoderOnly() {
            return new DurableContextMetadataDecoder<DecoderOnly>() {
                @Override
                public Class<DecoderOnly> type() {
                    return DecoderOnly.class;
                }

                @Override
                public String namespace() {
                    return "decoder-only";
                }

                @Override
                public ContextDecodeResult<DecoderOnly> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    return ContextDecodeResult.empty();
                }
            };
        }

        @Provides
        @IntoSet
        static InboundContextInitializer initializer(ContextHolder holder, Probe probe) {
            return context -> {
                probe.initializers.add(probe.observe(holder));
                ContextHolder.Scope installed = holder.bind(Initialized.class, new Initialized(context.boundary()));
                return () -> {
                    installed.close();
                    probe.closedInitializers++;
                };
            };
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> serviceRecorder(ContextHolder holder, Probe probe) {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    probe.services.add(probe.observe(holder));
                    if (!probe.failService) {
                        return Future.succeededFuture(RecorderResult.empty());
                    }
                    Promise<RecorderResult> failed = Promise.promise();
                    Vertx.currentContext()
                            .runOnContext(ignored ->
                                    failed.fail(new IllegalStateException("async workflow recorder failure")));
                    return failed.future();
                }
            };
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> eventRecorder(Probe probe) {
            OutboxService outbox = (tx, entry) -> {
                probe.events.add(assertInstanceOf(WorkflowEventEnvelope.class, entry.payload()));
                return Future.succeededFuture(1L);
            };
            WorkflowEventOutboxBinding binding =
                    new WorkflowEventOutboxBinding(DestinationType.SERVICE, "scope.events");
            OutboxDestinationHandler destination = new OutboxDestinationHandler() {
                @Override
                public DestinationType destinationType() {
                    return DestinationType.SERVICE;
                }

                @Override
                public ClaimScope claimScope() {
                    return ClaimScope.all();
                }

                @Override
                public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                    return Future.succeededFuture(OutboxPublishResult.success());
                }
            };
            return new WorkflowEventSideEffectRecorder(
                    outbox, binding, new WorkflowEventsComposeValidator(Set.of(destination), binding));
        }
    }

    @Singleton
    @Component(modules = {WorkflowPostgresqlModule.class, ContextRuntimeModule.class, FixtureModule.class})
    interface WorkflowGraph {
        WorkflowOperations operations();

        TransactionalWorkflowOperations<SqlClient> transactional();

        ContextHolder holder();

        DurableContextPropagator propagator();

        WorkflowRegistry registry();

        PgWorkflowDefinitionRegistryStore definitionStore();

        WorkflowDefinitionLifecycle definitionLifecycle();

        @Component.Factory
        interface Factory {
            WorkflowGraph create(@BindsInstance Pool pool, @BindsInstance Clock clock, @BindsInstance Probe probe);
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
        graph = DaggerWorkflowDurableScopeContextIT_WorkflowGraph.factory().create(pool, Clock.systemUTC(), probe);
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
    @DisplayName("production start captures exact instance and branch metadata and fresh drives restore acme")
    void capturesAndRestoresScopeAcrossInstanceAndBranchCarriers(VertxTestContext ctx) {
        start("scope-instance", ACME, null)
                .compose(id -> instanceRow(id).map(row -> {
                    assertEquals(
                            saved("acme").toCarrier(),
                            row.getJsonObject("metadata"),
                            "instance start must persist the exact captured scope");
                    return id;
                }))
                .compose(id -> onFreshDuplicate(() -> graph.operations().signal(id, "go", "go", "instance-go")))
                .compose(ignored -> start("scope-branch", ACME, null))
                .compose(id -> pool.preparedQuery(
                                "SELECT metadata FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id ="
                                        + " 'a'")
                        .execute(Tuple.of(id.value()))
                        .map(rows -> {
                            assertEquals(
                                    saved("acme").toCarrier(),
                                    rows.iterator().next().getJsonObject("metadata"),
                                    "branch creation must persist the exact captured scope");
                            return id;
                        }))
                .compose(id -> onFreshDuplicate(() -> signal(id, null, "fork", "a", "branch-go")))
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertEquals(2, probe.services.size());
                    probe.services.forEach(observation -> assertEquals(
                            Optional.of(ACME),
                            observation.scope(),
                            "a fresh instance or branch drive must restore the saved acme value"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("ambient and explicit caller scopes override saved scope without changing workflow scope fields")
    void callerScopeOverridesSavedScopeWithoutChangingWorkflowScopeFields(VertxTestContext ctx) {
        start("scope-instance", ACME, "orders")
                .compose(id -> onFreshDuplicate(
                                () -> withScope(scope("ambient-b"), () -> signal(id, null, null, null, "ambient")))
                        .map(id))
                .compose(id -> instanceRow(id).map(row -> {
                    assertWorkflowFields(row, "orders");
                    assertEquals(
                            saved("acme").toCarrier(),
                            row.getJsonObject("metadata"),
                            "caller override must retain the saved acme carrier");
                    return id;
                }))
                .compose(ignored -> start("scope-instance", ACME, "orders"))
                .compose(id -> onFreshDuplicate(() -> withScope(
                                scope("ambient-c"), () -> signal(id, saved("explicit-b"), null, null, "explicit")))
                        .map(id))
                .compose(id -> instanceRow(id).map(row -> {
                    assertWorkflowFields(row, "orders");
                    return id;
                }))
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertEquals(
                            List.of(Optional.of(scope("ambient-b")), Optional.of(scope("explicit-b"))),
                            probe.services.stream().map(Observation::scope).toList());
                    assertFalse(probe.events.isEmpty());
                    probe.events.forEach(event -> {
                        assertEquals("orders", event.scopeKey());
                        assertEquals("default", event.definitionScopeKey());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("ambient scope does not select workflow start scope or event-envelope scope fields")
    void ambientScopeDoesNotSelectWorkflowStartScopeOrEventEnvelope(VertxTestContext ctx) {
        start("scope-instance", ACME, null)
                .compose(WorkflowDurableScopeContextIT::instanceRow)
                .onComplete(ctx.succeeding(row -> ctx.verify(() -> {
                    assertWorkflowFields(row, "default");
                    assertFalse(probe.events.isEmpty(), "the production event recorder must emit an envelope");
                    probe.events.forEach(event -> {
                        assertEquals("default", event.scopeKey());
                        assertEquals("default", event.definitionScopeKey());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authoritative durable binding clears absent scope and close restores the prior binding")
    void authoritativeBindClearsAndCloseRestoresPreviousScope(VertxTestContext ctx) {
        onFreshDuplicate(() -> {
                    try (ContextHolder.Scope previous = graph.holder().bind(ScopeContext.class, ACME)) {
                        try (ContextHolder.Scope ingress =
                                graph.propagator().bindFrom(DurableMetadata.empty(), DispatchBoundary.WORKFLOW)) {
                            assertTrue(
                                    graph.holder().current(ScopeContext.class).isEmpty(),
                                    "the effective registry must clear absent scope authoritatively");
                        }
                        assertEquals(
                                ACME, graph.holder().current(ScopeContext.class).orElseThrow());
                    }
                    assertTrue(graph.holder().current(ScopeContext.class).isEmpty());
                    return Future.succeededFuture();
                })
                .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    @DisplayName("a seeded scope-only row opens the bind gate and runs the existing initializer lifecycle")
    void scopeOnlySavedMetadataOpensBindGateAndRunsExistingInitializerLifecycle(VertxTestContext ctx) {
        start("scope-instance", null, null)
                .compose(id -> seed(id, saved("saved-only")).map(id))
                .compose(id -> instanceRow(id).map(row -> {
                    assertEquals(
                            saved("saved-only").toCarrier(),
                            row.getJsonObject("metadata"),
                            "the gate fixture must contain exactly one nonempty scope namespace");
                    probe.reset();
                    return id;
                }))
                .compose(id -> onFreshDuplicate(() -> {
                    ContextHolder.Scope registered =
                            graph.holder().bind(DecoderOnly.class, new DecoderOnly("previous"));
                    ContextHolder.Scope unregistered =
                            graph.holder().bind(Unregistered.class, new Unregistered("keep"));
                    return graph.operations().signal(id, "go", "go", "gate").eventually(() -> {
                        try {
                            assertEquals(
                                    new DecoderOnly("previous"),
                                    graph.holder().current(DecoderOnly.class).orElseThrow());
                            assertEquals(
                                    new Unregistered("keep"),
                                    graph.holder().current(Unregistered.class).orElseThrow());
                            assertTrue(
                                    graph.holder().current(ScopeContext.class).isEmpty());
                            assertTrue(graph.holder().current(Initialized.class).isEmpty());
                        } finally {
                            unregistered.close();
                            registered.close();
                        }
                        return Future.succeededFuture();
                    });
                }))
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertEquals(
                            1,
                            probe.initializers.size(),
                            "the non-null scope-only carrier must open the existing bind gate");
                    Observation initializer = probe.initializers.getFirst();
                    assertEquals(
                            Optional.of(scope("saved-only")),
                            initializer.scope(),
                            "the initializer must observe decoded scope from the seeded row");
                    assertTrue(
                            initializer.registered().isEmpty(),
                            "an absent decoder-only registered value must be cleared");
                    assertEquals(Optional.of(new Unregistered("keep")), initializer.unregistered());
                    assertEquals(1, probe.closedInitializers);
                    assertEquals(
                            Optional.of(new Initialized(DispatchBoundary.WORKFLOW)),
                            probe.services.getFirst().initialized());
                    assertEquals(
                            Optional.of(scope("saved-only")),
                            probe.services.getFirst().scope());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a null instance carrier leaves ambient bindings alone and skips the bind gate")
    void nullCarrierControlLeavesHolderAndSkipsBindGate(VertxTestContext ctx) {
        start("scope-instance", null, null)
                .compose(id -> instanceRow(id).map(row -> {
                    assertNull(row.getValue("metadata"));
                    probe.reset();
                    return id;
                }))
                .compose(id -> onFreshDuplicate(() ->
                        withScope(scope("ambient"), () -> graph.operations().signal(id, "go", "go", "null-carrier"))))
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertEquals(
                            Optional.of(scope("ambient")),
                            probe.services.getFirst().scope());
                    assertTrue(
                            probe.initializers.isEmpty(),
                            "a null carrier must not run durable initializers even with ambient scope");
                    assertEquals(0, probe.closedInitializers);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an asynchronous workflow failure unwinds the installed saved scope and restores the prior value")
    void closesBindingAfterWorkflowAsyncFailure(VertxTestContext ctx) {
        start("scope-instance", null, null)
                .compose(id -> seed(id, saved("acme")).map(id))
                .compose(id -> {
                    probe.reset();
                    probe.failService = true;
                    return onFreshDuplicate(
                            () -> withScope(scope("previous"), () -> signal(id, saved("acme"), null, null, "failure")
                                    .transform(outcome -> {
                                        assertEquals(
                                                Optional.of(ACME),
                                                probe.services.getFirst().scope(),
                                                "saved scope must have been installed before the asynchronous failure");
                                        assertTrue(
                                                outcome.failed(),
                                                "the recorder's asynchronous failure must reach the workflow caller");
                                        assertEquals(
                                                scope("previous"),
                                                graph.holder()
                                                        .current(ScopeContext.class)
                                                        .orElseThrow(),
                                                "the failed drive must restore its caller's prior binding");
                                        assertEquals(probe.initializers.size(), probe.closedInitializers);
                                        return Future.succeededFuture();
                                    })));
                })
                .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    private static Future<WorkflowInstanceId> start(String definition, ScopeContext ambient, String startScope) {
        StartCommand command = new StartCommand(
                definition,
                Contract.class.getName(),
                new Start(UUID.randomUUID().toString()),
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                startScope,
                null);
        return onFreshDuplicate(() -> ambient == null
                ? graph.operations().start(command)
                : withScope(ambient, () -> graph.operations().start(command)));
    }

    private static Future<Void> signal(
            WorkflowInstanceId id, DurableMetadata explicit, String fork, String branch, String dedup) {
        return pool.withTransaction(
                tx -> graph.transactional().signal(id, "go", "go", dedup, fork, branch, explicit, tx));
    }

    private static Future<Void> seed(WorkflowInstanceId id, DurableMetadata metadata) {
        return pool.preparedQuery("UPDATE workflow_instances SET metadata = $1 WHERE id = $2")
                .execute(Tuple.of(metadata.toCarrier(), id.value()))
                .mapEmpty();
    }

    private static Future<Row> instanceRow(WorkflowInstanceId id) {
        return pool.preparedQuery(
                        "SELECT metadata, scope_key, definition_scope_key FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next());
    }

    private static void assertWorkflowFields(Row row, String startScope) {
        assertEquals(startScope, row.getString("scope_key"));
        assertEquals("default", row.getString("definition_scope_key"));
    }

    private static ScopeContext scope(String key) {
        return new ScopeContext(new ScopeKey(key));
    }

    private static DurableMetadata saved(String key) {
        return DurableMetadata.of("scope", new JsonObject().put("scopeKey", key));
    }

    private static <T> Future<T> withScope(ScopeContext value, Supplier<Future<T>> action) {
        ContextHolder.Scope scope = graph.holder().bind(ScopeContext.class, value);
        try {
            return action.get().eventually(() -> {
                scope.close();
                return Future.succeededFuture();
            });
        } catch (Throwable failure) {
            scope.close();
            return Future.failedFuture(failure);
        }
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
