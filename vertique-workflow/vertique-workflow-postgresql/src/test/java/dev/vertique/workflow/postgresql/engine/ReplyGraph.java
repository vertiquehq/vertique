// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.postgresql.engine;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertxModule;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.inboxoutbox.OutboxReplies;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowContributor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** An application with a workflow executor and an outbox relay over the same database. */
final class ReplyGraph implements AutoCloseable {
    static final String DEFINITION = "service-reply";

    record Start(String reference) {}

    record State(String reference, String result) {}

    record Reserved(String value) {}

    interface Contract {}

    interface Inventory {
        Future<Reserved> reserve(Start request);

        Future<Void> ack(Start request);

        Future<Void> later(Start request, TransactionalMessageContext context);
    }

    @Singleton
    static final class InventoryProbe implements Inventory {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger ackCalls = new AtomicInteger();
        final java.util.concurrent.ConcurrentMap<String, AtomicInteger> perReference =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile Promise<Reserved> held;
        volatile Promise<Reserved> heldGood;
        volatile Promise<Reserved> heldSecond;

        final jakarta.inject.Provider<OutboxReplies> replies;
        final java.util.concurrent.ConcurrentMap<String, java.util.List<String>> handles =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Inject
        InventoryProbe(jakarta.inject.Provider<OutboxReplies> replies) {
            this.replies = replies;
        }

        int callsFor(String reference) {
            var count = perReference.get(reference);
            return count == null ? 0 : count.get();
        }

        @Override
        public Future<Reserved> reserve(Start request) {
            calls.incrementAndGet();
            int number = perReference
                    .computeIfAbsent(request.reference(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (heldGood != null && request.reference().equals("good")) return heldGood.future();
            if (held != null && heldSecond != null && number > 1) return heldSecond.future();
            if (held != null && (!request.reference().equals("failure-then-success") || number == 1))
                return held.future();
            if (request.reference().equals("failure-then-success") && number == 2) return Future.failedFuture("retry");
            if (request.reference().equals("bad") || request.reference().equals("exhaust"))
                return Future.failedFuture(new IllegalStateException("SECRET-77"));
            if (request.reference().equals("permanent")) return Future.failedFuture(new PermanentProblem());
            if (request.reference().equals("nul")) return Future.succeededFuture(new Reserved("before\u0000after"));
            if (request.reference().equals("oversize"))
                return Future.succeededFuture(new Reserved("x".repeat(300_000)));
            return Future.succeededFuture(new Reserved("reserved-" + request.reference()));
        }

        @Override
        public Future<Void> later(Start request, TransactionalMessageContext context) {
            handles.computeIfAbsent(request.reference(), ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(context.replyHandle());
            if (request.reference().equals("callback-reply")
                    || request.reference().equals("callback-ordinary"))
                return Future.failedFuture(new IllegalStateException("SECRET-CALLBACK " + context.replyHandle()));
            if (request.reference().equals("fatal-redaction"))
                return Future.failedFuture(new AssertionError("SECRET-FATAL " + context.replyHandle()));
            if (request.reference().equals("fatal-ordinary"))
                return Future.failedFuture(new AssertionError("SECRET-ORDINARY"));
            if (request.reference().equals("redaction"))
                return Future.failedFuture(new PermanentProblem("SECRET-77 " + context.replyHandle()));
            if (request.reference().equals("early"))
                return replies.get()
                        .complete(context.replyHandle(), new Reserved("early"))
                        .mapEmpty();
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> ack(Start request) {
            ackCalls.incrementAndGet();
            return Future.succeededFuture();
        }
    }

    static final class PermanentProblem extends RuntimeException
            implements dev.vertique.inboxoutbox.OutboxPermanentFailure {
        PermanentProblem() {}

        PermanentProblem(String message) {
            super(message);
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
            return DEFINITION;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> new Start(state.reference()))
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("done")
                    .onFailure((state, failure) ->
                            new State(state.reference(), failure.reason() + ":" + failure.attempts()))
                    .toStepOnFailure("failed")
                    .build();
            wf.complete("done");
            wf.complete("failed");
        }
    }

    static final class LoopDefinition implements WorkflowDefinition<State, Contract> {
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
            return "service-reply-loop";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> new Start(state.reference()))
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("done")
                    .onFailure()
                    .toStepOnFailure("failed")
                    .timeoutAfter(Duration.ofSeconds(3))
                    .onTimeout(state -> state)
                    .toStepOnTimeout("reserve");
            wf.complete("done");
            wf.complete("failed");
        }
    }

    static final class VoidDefinition implements WorkflowDefinition<State, Contract> {
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
            return "service-reply-void";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("ack");
            wf.call("ack", "inventory.ack", state -> new Start(state.reference()))
                    .onReply(Void.class, (state, reply) -> new State(state.reference(), "acknowledged"))
                    .toStepOnReply("done")
                    .onFailure()
                    .toStepOnFailure("failed")
                    .build();
            wf.complete("done");
            wf.complete("failed");
        }
    }

    static final class TimedDefinition implements WorkflowDefinition<State, Contract> {
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
            return "service-reply-timed";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> new Start(state.reference()))
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("done")
                    .onFailure((state, failure) ->
                            new State(state.reference(), failure.reason() + ":" + failure.attempts()))
                    .toStepOnFailure("failed")
                    .timeoutAfter(Duration.ofSeconds(3))
                    .onTimeout(state -> new State(state.reference(), "timeout"))
                    .toStepOnTimeout("timed");
            wf.complete("done");
            wf.complete("failed");
            wf.complete("timed");
        }
    }

    static final class LongTimedDefinition implements WorkflowDefinition<State, Contract> {
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
            return "service-reply-long-timed";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> new Start(state.reference()))
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("done")
                    .onFailure((state, failure) ->
                            new State(state.reference(), failure.reason() + ":" + failure.attempts()))
                    .toStepOnFailure("failed")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(state -> new State(state.reference(), "timeout"))
                    .toStepOnTimeout("timed");
            wf.complete("done");
            wf.complete("failed");
            wf.complete("timed");
        }
    }

    static final class ForkDefinition implements WorkflowDefinition<State, Contract> {
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
            return "service-reply-fork";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("fork");
            wf.fork("fork").branch("a", "bad").branch("b", "good").join("join");
            wf.call("bad", "inventory.reserve", state -> new Start("bad"))
                    .onReply(Reserved.class, (state, reply) -> state)
                    .toStepOnReply("a-done")
                    .onFailure()
                    .toStepOnFailure("a-fail")
                    .build();
            wf.call("good", "inventory.reserve", state -> new Start("good"))
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("b-done")
                    .onFailure()
                    .toStepOnFailure("b-fail")
                    .build();
            wf.complete("a-done");
            wf.complete("a-fail");
            wf.complete("b-done");
            wf.complete("b-fail");
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .endJoin();
            wf.complete("done");
        }
    }

    static final class FailedForkDefinition implements WorkflowDefinition<State, Contract> {
        public Class<Contract> contract() {
            return Contract.class;
        }

        public Class<State> stateType() {
            return State.class;
        }

        public String definitionId() {
            return "service-reply-failed-fork";
        }

        public long definitionVersion() {
            return 1;
        }

        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("fork");
            wf.fork("fork").branch("a", "bad").branch("b", "good").join("join");
            wf.call("bad", "inventory.reserve", state -> new Start("permanent"))
                    .onReply(Reserved.class, (state, reply) -> state)
                    .toStepOnReply("a-done")
                    .onFailure()
                    .toStepOnFailure("a-fail")
                    .build();
            wf.call("good", "inventory.reserve", state -> new Start("good"))
                    .compensateWith("undo-good")
                    .onReply(Reserved.class, (state, reply) -> state)
                    .toStepOnReply("b-done")
                    .onFailure()
                    .toStepOnFailure("b-fail")
                    .build();
            wf.complete("a-done");
            wf.fail("a-fail", "FAILED", state -> "failed");
            wf.complete("b-done");
            wf.complete("b-fail");
            wf.compensate("undo-good", "good", "inventory.ack", state -> new Start("undo"));
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("fork-failed")
                    .endJoin();
            wf.fail("fork-failed", "FORK_FAILED", state -> "fork failed");
            wf.complete("done");
        }
    }

    static final class LaterDefinition implements WorkflowDefinition<State, Contract> {
        private final long version;

        LaterDefinition(long version) {
            this.version = version;
        }

        public Class<Contract> contract() {
            return Contract.class;
        }

        public Class<State> stateType() {
            return State.class;
        }

        public String definitionId() {
            return "service-reply-later";
        }

        public long definitionVersion() {
            return version;
        }

        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.reference(), null)).initialStep("reserve");
            wf.call("reserve", "inventory.later", state -> new Start(state.reference()))
                    .completesLater()
                    .onReply(Reserved.class, (state, reply) -> new State(state.reference(), reply.value()))
                    .toStepOnReply("done")
                    .onFailure((state, failure) ->
                            new State(state.reference(), failure.reason() + ":" + failure.attempts()))
                    .toStepOnFailure("failed")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(state -> new State(state.reference(), "timeout"))
                    .toStepOnTimeout("timed");
            wf.complete("done");
            wf.complete("failed");
            wf.complete("timed");
        }
    }

    static final class LaterMigration
            implements dev.vertique.workflow.migration.WorkflowMigrationHandler<State, State> {
        public String definitionId() {
            return "service-reply-later";
        }

        public long fromDefinitionVersion() {
            return 1L;
        }

        public long targetDefinitionVersion() {
            return 2L;
        }

        public Class<State> sourceStateType() {
            return State.class;
        }

        public Class<State> targetStateType() {
            return State.class;
        }

        public dev.vertique.workflow.migration.MigrationResult<State> migrate(
                State state, dev.vertique.workflow.migration.MigrationContext context) {
            return dev.vertique.workflow.migration.MigrationResult.continueAt(state, "reserve.reply");
        }
    }

    @Singleton
    static final class ReplyProbe implements dev.vertique.workflow.ops.ReplyOutcomeObserver {
        final java.util.List<Outcome> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<io.vertx.core.Context> dispatchContext =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<io.vertx.core.Context> foreignContext =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<io.vertx.core.Context> terminalContext =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Inject
        ReplyProbe() {}

        @Override
        public void onOutcome(Outcome outcome) {
            outcomes.add(outcome);
        }
    }

    @Module
    static class ReplyModule {
        @Provides
        @IntoSet
        static dev.vertique.services.interceptor.ServiceInterceptor failedCallback(ReplyProbe probe, Vertx vertx) {
            io.vertx.core.Context foreign = vertx.getOrCreateContext();
            return new dev.vertique.services.interceptor.ServiceInterceptor() {
                private boolean terminalCase(dev.vertique.services.interceptor.ServiceDispatchContext ctx) {
                    return ctx.envelope().payload() instanceof Start start
                            && start.reference().startsWith("foreign-terminal-");
                }

                @Override
                public void onComplete(
                        dev.vertique.services.interceptor.ServiceDispatchContext ctx,
                        dev.vertique.core.eventbus.Result<?> result,
                        java.time.Instant start,
                        java.time.Instant end) {
                    if (terminalCase(ctx)) probe.dispatchContext.set(Vertx.currentContext());
                }

                @Override
                public Future<Void> afterDispatch(
                        dev.vertique.services.interceptor.ServiceDispatchContext ctx,
                        dev.vertique.core.eventbus.Result<?> result) {
                    if (terminalCase(ctx)) {
                        Promise<Void> pending = Promise.promise();
                        foreign.runOnContext(ignored -> vertx.setTimer(10, id -> {
                            probe.foreignContext.set(Vertx.currentContext());
                            pending.complete();
                        }));
                        return pending.future();
                    }
                    if (result.isFailure()
                            && result.cause() != null
                            && String.valueOf(result.cause().getMessage()).contains("SECRET-CALLBACK"))
                        return Future.failedFuture(result.cause());
                    return Future.succeededFuture();
                }

                @Override
                public void onTerminalComplete(
                        dev.vertique.services.interceptor.ServiceDispatchContext ctx,
                        dev.vertique.core.eventbus.Result<?> result,
                        java.time.Instant start,
                        java.time.Instant end) {
                    if (!terminalCase(ctx)) return;
                    probe.terminalContext.set(Vertx.currentContext());
                    var message = (TransactionalMessageContext) ctx.envelope()
                            .metadata()
                            .dispatchContext()
                            .get(TransactionalMessageContext.class.getName());
                    throw new AssertionError("SECRET-TERMINAL " + (message == null ? null : message.replyHandle()));
                }
            };
        }

        @Provides
        @IntoSet
        static dev.vertique.workflow.ops.ReplyOutcomeObserver replyObserver(ReplyProbe probe) {
            return probe;
        }

        @Provides
        @IntoSet
        static WorkflowContributor workflow() {
            return registry -> {
                registry.register(new Definition());
                registry.register(new LaterDefinition(1));
                registry.register(new LaterDefinition(2));
                registry.register(new ForkDefinition());
                registry.register(new FailedForkDefinition());
                registry.register(new TimedDefinition());
                registry.register(new LongTimedDefinition());
                registry.register(new VoidDefinition());
                registry.register(new LoopDefinition());
            };
        }

        @Provides
        @IntoSet
        static dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?> migration() {
            return new LaterMigration();
        }

        @Provides
        @IntoSet
        static ServiceContractContributor inventory(InventoryProbe probe) {
            return config -> List.of(ServiceContractEntries.deployable()
                    .contract(Inventory.class)
                    .serviceInstance(probe)
                    .name("inventory")
                    .operation("reserve")
                    .method(reserveMethod())
                    .payloadType(Start.class)
                    .returnType(Reserved.class)
                    .param("payload", ParamSource.PAYLOAD, Start.class)
                    .done()
                    .operation("ack")
                    .method(ackMethod())
                    .payloadType(Start.class)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, Start.class)
                    .done()
                    .operation("later")
                    .method(laterMethod())
                    .payloadType(Start.class)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, Start.class)
                    .param("context", ParamSource.DISPATCH_CONTEXT, TransactionalMessageContext.class)
                    .done()
                    .deploymentOptions(config, "services", "inventory")
                    .build());
        }

        private static java.lang.reflect.Method laterMethod() {
            try {
                return Inventory.class.getMethod("later", Start.class, TransactionalMessageContext.class);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static java.lang.reflect.Method ackMethod() {
            try {
                return Inventory.class.getMethod("ack", Start.class);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static java.lang.reflect.Method reserveMethod() {
            try {
                return Inventory.class.getMethod("reserve", Start.class);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                RelayGraph.Shared.class,
                RelayGraph.Infrastructure.class,
                ReplyModule.class,
                dev.vertique.workflow.migration.WorkflowMigrationModule.class,
                dev.vertique.correlation.CorrelationContextModule.class
            })
    interface ComponentGraph extends RelayGraph.Graph {
        dev.vertique.inboxoutbox.OutboxRepository outboxRepository();

        dev.vertique.workflow.engine.executor.WorkflowExecutor executor();

        dev.vertique.deploy.VerticleDeployer deployer();

        dev.vertique.context.DurableContextPropagator contextPropagator();

        InventoryProbe inventoryProbe();

        ReplyProbe replyProbe();

        Optional<OutboxOutcomeConsumer> outcomeConsumer();

        OutboxReplies replies();

        dev.vertique.workflow.ops.RelayedSignals<io.vertx.sqlclient.SqlClient> relayedSignals();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            Builder infrastructure(RelayGraph.Infrastructure module);

            ComponentGraph build();
        }
    }

    final Vertx vertx;
    final Pool pool;
    final ComponentGraph graph;
    final VertiqueApplicationHandle<?> application;

    ReplyGraph(Vertx vertx, PostgresContainer db) {
        this(vertx, db, 10_000);
    }

    ReplyGraph(Vertx vertx, PostgresContainer db, long sendTimeoutMs) {
        this(vertx, db, sendTimeoutMs, false);
    }

    ReplyGraph(Vertx vertx, PostgresContainer db, long sendTimeoutMs, boolean singleAttempt) {
        this(vertx, db, sendTimeoutMs, singleAttempt, 1000);
    }

    ReplyGraph(Vertx vertx, PostgresContainer db, long sendTimeoutMs, boolean singleAttempt, long leaseTimeoutMs) {
        this.vertx = vertx;
        PgConnectOptions options = RelayGraph.options(db);
        pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
        JsonObject config = RelayGraph.config(null);
        config.getJsonObject("inboxOutbox")
                .getJsonObject("relay")
                .put("leaseTimeoutMs", leaseTimeoutMs)
                .put("backoffBaseDelayMs", 100)
                .put("instances", singleAttempt ? 1 : 2);
        if (singleAttempt) {
            config.getJsonObject("inboxOutbox")
                    .getJsonObject("relay")
                    .put("batchSize", 1)
                    .put("leaseTimeoutMs", 60_000);
        }
        config.put("services", new JsonObject().put("sendTimeoutMs", sendTimeoutMs));
        graph = DaggerReplyGraph_ComponentGraph.builder()
                .vertxModule(new VertxModule(vertx, config))
                .infrastructure(new RelayGraph.Infrastructure(pool, options))
                .build();
        try {
            application = await(RelayGraph.start(vertx, graph));
        } catch (RuntimeException | Error e) {
            await(pool.close());
            throw e;
        }
    }

    void pauseExecutor() {
        await(graph.deployer().undeploy("workflow-executor"));
    }

    void resumeExecutor() {
        await(graph.deployer().deploy(graph.executor().deployment()));
    }

    WorkflowInstanceId startCall(String ref) {
        return start(DEFINITION, ref);
    }

    WorkflowInstanceId start(String definition, String ref) {
        var command = new StartCommand(
                definition, Contract.class.getName(), new Start(ref), ref, null, null, null, null, null);
        return await(pool.withTransaction(tx -> graph.transactionalOperations().start(command, tx)));
    }

    WorkflowInstanceId startLater(String ref) {
        var command = new StartCommand(
                "service-reply-later", Contract.class.getName(), new Start(ref), ref, null, null, 1L, null, null);
        return await(pool.withTransaction(tx -> graph.transactionalOperations().start(command, tx)));
    }

    String storedHandle(String ref, int index) {
        until(
                "stored handle",
                () -> graph.inventoryProbe()
                                .handles
                                .getOrDefault(ref, List.of())
                                .size()
                        > index);
        return graph.inventoryProbe().handles.get(ref).get(index);
    }

    WorkflowInstanceId startCorrelated(String ref, dev.vertique.core.correlation.CorrelationContext correlation) {
        Promise<WorkflowInstanceId> result = Promise.promise();
        io.vertx.core.internal.ContextInternal caller =
                ((io.vertx.core.internal.ContextInternal) vertx.getOrCreateContext()).duplicate();
        caller.runOnContext(ignored -> {
            var binding =
                    graph.contextHolder().bind(dev.vertique.core.correlation.CorrelationContext.class, correlation);
            var command = new StartCommand(
                    DEFINITION, Contract.class.getName(), new Start(ref), ref, null, null, null, null, null);
            pool.withTransaction(tx -> graph.transactionalOperations().start(command, tx))
                    .onComplete(done -> {
                        binding.close();
                        result.handle(done);
                    });
        });
        return await(result.future());
    }

    void limitAttempts() {
        await(pool.query("""
                CREATE FUNCTION reply_limit_attempts() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.max_attempts := 2;
                    IF NEW.payload->>'reference' = 'decode' THEN NEW.payload := '{"reference":{"secret":"SECRET-DECODE-77"}}'::jsonb; END IF;
                    RETURN NEW;
                END $$
                """).execute());
        await(pool.query(
                        "CREATE TRIGGER reply_limit_attempts BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION reply_limit_attempts()")
                .execute());
    }

    void removeAttemptLimit() {
        await(pool.query("DROP TRIGGER reply_limit_attempts ON outbox").execute());
        await(pool.query("DROP FUNCTION reply_limit_attempts()").execute());
    }

    Map<String, Object> dispatchContext(Row row) {
        String carrierId = row.getUUID("carrier_id").toString();
        var target = new dev.vertique.core.context.DurableTarget("outbox-relay", carrierId, Optional.empty());
        var carrier = new dev.vertique.core.context.DurableCarrierDescriptor(carrierId, target);
        var metadata = dev.vertique.core.context.DurableMetadata.fromCarrier(json(row, "metadata"));
        return graph.contextPropagator()
                .decodeToDispatchContext(metadata, dev.vertique.core.context.DispatchBoundary.OUTBOX_SERVICE, carrier);
    }

    static JsonObject json(Row row, String column) {
        Object value = row.getValue(column);
        return value instanceof JsonObject object ? object : new JsonObject(String.valueOf(value));
    }

    List<Row> rows(String sql, Object... args) {
        List<Row> result = new ArrayList<>();
        await(pool.preparedQuery(sql).execute(Tuple.tuple(List.of(args)))).forEach(result::add);
        return result;
    }

    Row row(WorkflowInstanceId id) {
        return rows(
                        "SELECT * FROM outbox WHERE headers->>'x-workflow-id' = $1",
                        id.value().toString())
                .stream()
                .findFirst()
                .orElse(null);
    }

    Row instance(WorkflowInstanceId id) {
        return rows("SELECT * FROM workflow_instances WHERE id = $1", id.value())
                .getFirst();
    }

    List<Row> signals(WorkflowInstanceId id) {
        return rows(
                "SELECT * FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL' ORDER BY seq", id.value());
    }

    void until(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(45, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Future failed", e);
        }
    }

    @Override
    public void close() {
        try {
            await(application.shutdown());
        } finally {
            await(pool.close());
        }
    }
}
