// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.ReplySignalsTestBridge;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.NodeSpec;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
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
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class ReplyStepFixture implements AutoCloseable {
    static final String DEFINITION = "reply-order";
    static final String REPLY = "vertique.reply.ok.reserve";

    record Start(String ref) {}

    record State(String ref, String reserved) {}

    record Reserved(String reference) {}

    interface Contract {
        @WorkflowSignal("other")
        Future<Void> other(WorkflowInstanceId id, String payload, @SignalDedupKey String key);
    }

    record WaitRecord(
            UUID waitId,
            String stepId,
            String contractName,
            String signalName,
            String correlationKey,
            String forkStepId,
            String branchId) {}

    record Branch(String status, String stepId) {}

    enum Mode {
        ORIGINAL,
        REDUCER,
        IDENTITY,
        TIMED,
        BROKEN_TIMEOUT,
        COMPENSATION,
        FORK,
        FORK_LOOP,
        FORK_FAILURE,
        APPLY_FAILURE,
        NEXT_FAILURE,
        STEP_LIMIT,
        DRAIN_STEP_LIMIT,
        FORK_APPLY_FAILURE
    }

    final java.util.concurrent.atomic.AtomicBoolean timeoutBroken = new java.util.concurrent.atomic.AtomicBoolean(true);
    final java.util.concurrent.atomic.AtomicBoolean reducerBroken = new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger heavyVisits = new java.util.concurrent.atomic.AtomicInteger();
    private Node immediateReplyNode;
    final ExecutorFixture fixture;
    final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
    final PgWorkflowDefinitionRegistryStore store;
    final String contractName;

    private ReplyStepFixture(DbPoolConfig db, Mode mode) {
        fixture = ExecutorFixture.open(db);
        try {
            store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            registry.register(new Definition(mode, timeoutBroken, reducerBroken, heavyVisits));
            var runtime = registry.codeDefinitions().stream()
                    .filter(r -> r.plan().definitionId().equals(DEFINITION))
                    .findFirst()
                    .orElseThrow();
            contractName = runtime.contractName();
            Instant now = Instant.now();
            await(store.upsertCode(
                    new WorkflowDefinitionRecord(
                            new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION, 1L),
                            DefinitionKind.CODE,
                            contractName,
                            runtime.plan().stateTypeName(),
                            runtime.plan().planHash(),
                            DefinitionStatus.AVAILABLE,
                            new DefinitionProvenance("code", "test", null, null, null, now),
                            null,
                            now,
                            now,
                            Map.of(),
                            null,
                            new JsonObject()
                                    .put(
                                            "instance",
                                            new JsonArray(List.of(
                                                    REPLY,
                                                    "vertique.reply.failed.reserve",
                                                    "vertique.reply.ok.first",
                                                    "vertique.reply.failed.first",
                                                    "vertique.reply.ok.second",
                                                    "vertique.reply.failed.second",
                                                    "other")))
                                    .put(
                                            "branches",
                                            new JsonObject()
                                                    .put(
                                                            "fork/a",
                                                            new JsonArray(List.of(
                                                                    "vertique.reply.ok.first",
                                                                    "vertique.reply.failed.first")))
                                                    .put(
                                                            "fork/b",
                                                            new JsonArray(List.of(
                                                                    "vertique.reply.ok.second",
                                                                    "vertique.reply.failed.second")))),
                            null),
                    true));
            await(fixture.observer()
                    .query(
                            "CREATE TABLE IF NOT EXISTS reply_test_intent (instance_id UUID NOT NULL, wait_id UUID NOT NULL)")
                    .execute());
            await(fixture.observer().query("TRUNCATE reply_test_intent").execute());
            await(fixture.observer()
                    .query("CREATE TABLE IF NOT EXISTS reply_test_targets "
                            + "(instance_id UUID NOT NULL, target TEXT NOT NULL)")
                    .execute());
            await(fixture.observer().query("TRUNCATE reply_test_targets").execute());
        } catch (RuntimeException | AssertionError e) {
            fixture.close();
            throw e;
        }
    }

    static ReplyStepFixture open(DbPoolConfig db) {
        return new ReplyStepFixture(db, Mode.ORIGINAL);
    }

    static ReplyStepFixture open(DbPoolConfig db, Mode mode) {
        return new ReplyStepFixture(db, mode);
    }

    void parkReplyDuringDispatch(Node node) {
        immediateReplyNode = node;
    }

    void breakReducer() {
        reducerBroken.set(true);
    }

    void mendReducer() {
        reducerBroken.set(false);
    }

    NodeSpec withStepCap(int cap) {
        return node().maxStepsPerSegment(cap);
    }

    NodeSpec node() {
        return fixture.node("reply")
                .withRegistry(registry)
                .definitionStore(store)
                .carryContext()
                .recorders(new Recorder());
    }

    WorkflowInstanceId startCall(Node node) {
        return node.record(new StartCommand(
                DEFINITION,
                contractName,
                new Start("order"),
                UUID.randomUUID().toString(),
                null,
                null,
                1L,
                null,
                null));
    }

    List<WaitRecord> waitRecords(WorkflowInstanceId id) {
        List<WaitRecord> result = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery(
                        "SELECT wait_id, step_id, contract_name, signal_name, correlation_key, fork_step_id, branch_id FROM workflow_signal_waits WHERE instance_id = $1")
                .execute(Tuple.of(id.value())))) {
            result.add(new WaitRecord(
                    row.getUUID("wait_id"),
                    row.getString("step_id"),
                    row.getString("contract_name"),
                    row.getString("signal_name"),
                    row.getString("correlation_key"),
                    row.getString("fork_step_id"),
                    row.getString("branch_id")));
        }
        return result;
    }

    WorkflowInstanceId startForkOfTwoCalls(Node node) {
        return startCall(node);
    }

    WaitRecord branchWait(WorkflowInstanceId id, String branchId) {
        return waitRecords(id).stream()
                .filter(record -> branchId.equals(record.branchId()))
                .findFirst()
                .orElseThrow();
    }

    Branch branch(WorkflowInstanceId id, String branchId) {
        Row row = await(fixture.observer()
                        .preparedQuery(
                                "SELECT status, current_step_id FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id = $2")
                        .execute(Tuple.of(id.value(), branchId)))
                .iterator()
                .next();
        return new Branch(row.getString("status"), row.getString("current_step_id"));
    }

    List<String> targets(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT target FROM reply_test_targets WHERE instance_id = $1 ORDER BY target")
                        .execute(Tuple.of(id.value())))
                .stream()
                .map(row -> row.getString("target"))
                .toList();
    }

    void expireWait(WorkflowInstanceId id, String branchId) {
        Row row = await(fixture.observer()
                        .preparedQuery(
                                "UPDATE workflow_commands SET fire_at = clock_timestamp(), available_at = clock_timestamp() "
                                        + "WHERE workflow_id = $1 AND kind = 'TIMER' AND timer_kind = 'TIMEOUT' "
                                        + "AND state = 'PENDING' AND target_json->>'branchId' = $2 RETURNING id, fire_at")
                        .execute(Tuple.of(id.value(), branchId)))
                .iterator()
                .next();
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE command_id = $1")
                .execute(Tuple.of(row.getLong("id"), row.getOffsetDateTime("fire_at"))));
    }

    List<UUID> intents(WorkflowInstanceId id) {
        List<UUID> result = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT wait_id FROM reply_test_intent WHERE instance_id = $1")
                .execute(Tuple.of(id.value())))) result.add(row.getUUID("wait_id"));
        return result;
    }

    CorrelatedSignalReceipt signalWait(Node node, WorkflowInstanceId id, UUID waitId, Object value) {
        return await(caller(() -> {
            ContextHolder.Scope scope = fixture.holder()
                    .bind(
                            SecurityContext.class,
                            SecurityContexts.unauthenticated(SystemIdentities.internal("outbox reply")));
            try {
                return node.pool
                        .withTransaction(tx -> ReplySignalsTestBridge.relayedSignals(node.engine)
                                .signalWait(id, waitId, value, SignalProvenance.outbox("inventory.reserve", 7L), tx))
                        .eventually(() -> {
                            scope.close();
                            return Future.succeededFuture();
                        });
            } catch (Throwable e) {
                scope.close();
                return Future.failedFuture(e);
            }
        }));
    }

    CorrelatedSignalReceipt failWait(Node node, WorkflowInstanceId id, UUID waitId, ServiceFailure failure) {
        return await(caller(() -> {
            ContextHolder.Scope scope = fixture.holder()
                    .bind(
                            SecurityContext.class,
                            SecurityContexts.unauthenticated(SystemIdentities.internal("outbox reply")));
            try {
                return node.pool
                        .withTransaction(tx -> ReplySignalsTestBridge.relayedSignals(node.engine)
                                .failWait(id, waitId, failure, SignalProvenance.outbox("inventory.reserve", 7L), tx))
                        .eventually(() -> {
                            scope.close();
                            return Future.succeededFuture();
                        });
            } catch (Throwable e) {
                scope.close();
                return Future.failedFuture(e);
            }
        }));
    }

    void retry(Node node, WorkflowInstanceId id) {
        await(caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
    }

    void advancePastTimeout(WorkflowInstanceId id) {
        await(fixture.observer()
                .preparedQuery("SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (fire_at - clock_timestamp())) + 0.1)) "
                        + "FROM workflow_commands WHERE workflow_id = $1 AND kind = 'TIMER' AND timer_kind = 'TIMEOUT'")
                .execute(Tuple.of(id.value())));
    }

    void makeTimeoutDue(WorkflowInstanceId id) {
        Row row = await(fixture.observer()
                        .preparedQuery(
                                "UPDATE workflow_commands SET fire_at = clock_timestamp(), available_at = clock_timestamp() "
                                        + "WHERE workflow_id = $1 AND kind = 'TIMER' AND timer_kind = 'TIMEOUT' "
                                        + "AND state = 'PENDING' RETURNING id, fire_at")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE command_id = $1")
                .execute(Tuple.of(row.getLong("id"), row.getOffsetDateTime("fire_at"))));
    }

    void deferTimeout(WorkflowInstanceId id) {
        Row row = await(fixture.observer()
                        .preparedQuery(
                                "UPDATE workflow_commands SET state = 'COMPLETE', fire_at = clock_timestamp() + interval '1 hour', available_at = clock_timestamp() + interval '1 hour' "
                                        + "WHERE workflow_id = $1 AND kind = 'TIMER' AND timer_kind = 'TIMEOUT' RETURNING id, fire_at")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE command_id = $1")
                .execute(Tuple.of(row.getLong("id"), row.getOffsetDateTime("fire_at"))));
    }

    boolean waitOpen(Node node, WorkflowInstanceId id, UUID waitId) {
        return await(node.pool.withTransaction(
                tx -> ReplySignalsTestBridge.relayedSignals(node.engine).waitOpen(id, waitId, tx)));
    }

    long openSignals(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT open_signals FROM workflow_command_queues WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getInteger(0);
    }

    void fillOpenSignals(Node node, WorkflowInstanceId id) {
        for (int i = 0; i < 100; i++) {
            String key = "fill-" + i;
            await(node.pool.withTransaction(tx -> node.engine.signal(id, "other", "queued", key, tx)));
        }
    }

    <T> Future<T> caller(Supplier<Future<T>> work) {
        Promise<T> promise = Promise.promise();
        ContextInternal context = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            try {
                work.get().onComplete(promise);
            } catch (Throwable e) {
                promise.tryFail(e);
            }
        });
        return promise.future();
    }

    @Override
    public void close() {
        fixture.close();
    }

    private static final class Definition implements WorkflowDefinition<State, Contract> {
        private final Mode mode;
        private final java.util.concurrent.atomic.AtomicBoolean timeoutBroken;

        private final java.util.concurrent.atomic.AtomicBoolean reducerBroken;

        private final java.util.concurrent.atomic.AtomicInteger heavyVisits;

        Definition(
                Mode mode,
                java.util.concurrent.atomic.AtomicBoolean timeoutBroken,
                java.util.concurrent.atomic.AtomicBoolean reducerBroken,
                java.util.concurrent.atomic.AtomicInteger heavyVisits) {
            this.mode = mode;
            this.timeoutBroken = timeoutBroken;
            this.reducerBroken = reducerBroken;
            this.heavyVisits = heavyVisits;
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
            return DEFINITION;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), null));
            if (mode == Mode.FORK
                    || mode == Mode.FORK_LOOP
                    || mode == Mode.FORK_FAILURE
                    || mode == Mode.FORK_APPLY_FAILURE) {
                defineFork(wf);
                return;
            }
            if (mode == Mode.COMPENSATION) {
                wf.initialStep("first");
                wf.call("first", "service.first", state -> state.ref())
                        .compensateWith("undo-first")
                        .onReply(Reserved.class, (state, value) -> state)
                        .toStepOnReply("second")
                        .onFailure()
                        .toStepOnFailure("failed")
                        .build();
                wf.call("second", "service.second", state -> state.ref())
                        .compensateWith("undo-second")
                        .onReply(ServiceFailure.class, (state, value) -> state)
                        .toStepOnReply("failed")
                        .onFailure()
                        .toStepOnFailure("failed")
                        .build();
                wf.fail("failed", "FAILED", state -> "failed");
                wf.compensate("undo-first", "first", "undo.first", state -> state.ref());
                wf.compensate("undo-second", "second", "undo.second", state -> state.ref());
                wf.complete("done");
                return;
            }
            if (mode == Mode.DRAIN_STEP_LIMIT) {
                wf.initialStep("before-0");
                for (int i = 0; i < 15; i++) {
                    String next = i == 14 ? "reserve" : "before-" + (i + 1);
                    wf.decide("before-" + i, state -> next);
                }
                wf.call("reserve", "inventory.reserve", state -> state.ref())
                        .onReply(Reserved.class, (state, value) -> new State(state.ref(), value.reference()))
                        .toStepOnReply("spin-0")
                        .onFailure()
                        .toStepOnFailure("done")
                        .build();
                for (int i = 0; i < 40; i++) {
                    String next = i == 39 ? "after" : "spin-" + (i + 1);
                    wf.decide("spin-" + i, state -> {
                        heavyVisits.incrementAndGet();
                        return next;
                    });
                }
                wf.timer("after", java.time.Duration.ofHours(1)).toStep("done");
                wf.complete("done");
                return;
            }
            if (mode == Mode.APPLY_FAILURE || mode == Mode.NEXT_FAILURE || mode == Mode.STEP_LIMIT) {
                wf.initialStep("first");
                wf.call("first", "service.first", state -> state.ref())
                        .compensateWith("undo-first")
                        .onReply(Reserved.class, (state, value) -> state)
                        .toStepOnReply("reserve")
                        .onFailure()
                        .toStepOnFailure("done")
                        .build();
                wf.compensate("undo-first", "first", "undo.first", state -> state.ref());
                wf.call("reserve", "inventory.reserve", state -> state.ref())
                        .onReply(Reserved.class, (state, value) -> {
                            if (reducerBroken.get()) throw new IllegalStateException("SECRET-77");
                            return new State(
                                    state.ref(),
                                    (state.reserved() == null ? "" : state.reserved()) + value.reference());
                        })
                        .toStepOnReply(
                                mode == Mode.NEXT_FAILURE ? "next-call" : mode == Mode.STEP_LIMIT ? "spin-0" : "after")
                        .onFailure((state, value) -> {
                            if (reducerBroken.get()) throw new IllegalStateException("SECRET-77");
                            return new State(
                                    state.ref(),
                                    (state.reserved() == null ? "" : state.reserved()) + value.reason() + ":"
                                            + value.attempts());
                        })
                        .toStepOnFailure("failure-route")
                        .build();
                if (mode == Mode.NEXT_FAILURE) {
                    wf.call("next-call", "service.next", state -> {
                                throw new IllegalStateException("SECRET-77");
                            })
                            .onReply(Reserved.class, (state, value) -> state)
                            .toStepOnReply("done")
                            .onFailure()
                            .toStepOnFailure("done")
                            .build();
                }
                if (mode == Mode.STEP_LIMIT) {
                    for (int i = 0; i < 60; i++) {
                        String next = i == 59 ? "after" : "spin-" + (i + 1);
                        wf.decide("spin-" + i, state -> {
                            heavyVisits.incrementAndGet();
                            return next;
                        });
                    }
                }
                wf.timer("after", java.time.Duration.ofHours(1)).toStep("done");
                wf.timer("failure-route", java.time.Duration.ofHours(1)).toStep("done");
                wf.complete("done");
                return;
            }
            if (mode != Mode.ORIGINAL) {
                wf.initialStep("reserve");
                var call = wf.call("reserve", "inventory.reserve", state -> state.ref())
                        .onReply(Reserved.class, (state, value) -> new State(state.ref(), value.reference()))
                        .toStepOnReply("after");
                var failure = mode == Mode.IDENTITY
                        ? call.onFailure()
                        : call.onFailure(
                                (state, value) -> new State(state.ref(), value.reason() + ":" + value.attempts()));
                var wait = failure.toStepOnFailure("failure-route");
                if (mode == Mode.TIMED || mode == Mode.BROKEN_TIMEOUT) {
                    wait.timeoutAfter(
                                    mode == Mode.TIMED
                                            ? java.time.Duration.ofSeconds(5)
                                            : java.time.Duration.ofHours(1))
                            .onTimeout(state -> {
                                if (mode == Mode.BROKEN_TIMEOUT && timeoutBroken.get())
                                    throw new IllegalStateException("timeout");
                                return state;
                            })
                            .toStepOnTimeout("timed-out");
                    wf.complete("timed-out");
                } else {
                    wait.build();
                }
                wf.timer("failure-route", java.time.Duration.ofHours(1)).toStep("done");
                wf.timer("after", java.time.Duration.ofHours(1)).toStep("done");
                wf.complete("done");
                return;
            }
            wf.initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> Map.of("ref", state.ref()))
                    .onReply(Reserved.class, (state, value) -> new State(state.ref(), value.reference()))
                    .toStepOnReply("after")
                    .onFailure()
                    .toStepOnFailure("done")
                    .build();
            wf.timer("after", java.time.Duration.ofHours(1)).toStep("later");
            wf.waitForSignal("later", "other", String.class)
                    .onSignal((state, value) -> state)
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        }

        private void defineFork(WorkflowBuilder<State> wf) {
            wf.initialStep("fork");
            wf.fork("fork")
                    .branch("a", "first", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "second", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            var first = wf.call("first", "service.first", state -> state.ref());
            if (mode == Mode.FORK_FAILURE) first.compensateWith("undo-first");
            var wait = first.onReply(Reserved.class, (state, value) -> new State(state.ref(), value.reference()))
                    .toStepOnReply("a-done")
                    .onFailure((state, failure) -> {
                        if (mode == Mode.FORK_APPLY_FAILURE && reducerBroken.get())
                            throw new IllegalStateException("SECRET-77");
                        return new State(state.ref(), failure.reason() + ":" + failure.attempts());
                    })
                    .toStepOnFailure(mode == Mode.FORK_FAILURE ? "a-fail" : "a-failure");
            if (mode == Mode.FORK_LOOP) {
                wait.timeoutAfter(java.time.Duration.ofHours(1))
                        .onTimeout(state -> state)
                        .toStepOnTimeout("first");
            } else {
                wait.build();
            }
            wf.call("second", "service.second", state -> state.ref())
                    .onReply(Reserved.class, (state, value) -> new State(state.ref(), value.reference()))
                    .toStepOnReply("b-done")
                    .onFailure((state, failure) -> new State(state.ref(), failure.reason() + ":" + failure.attempts()))
                    .toStepOnFailure("b-failure")
                    .build();
            wf.complete("a-done");
            wf.complete("b-done");
            wf.complete("a-failure");
            wf.complete("b-failure");
            if (mode == Mode.FORK_FAILURE) {
                wf.fail("a-fail", "FAILED", state -> "failed");
                wf.compensate("undo-first", "first", "undo.first", state -> state.ref());
            }
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("fork-failed")
                    .endJoin();
            wf.fail("fork-failed", "FORK_FAILED", state -> "fork failed");
            wf.complete("done");
        }
    }

    private final class Recorder implements WorkflowSideEffectRecorder<SqlClient> {
        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            return tx.preparedQuery("INSERT INTO reply_test_targets (instance_id, target) VALUES ($1, $2)")
                    .execute(Tuple.of(intent.correlation().workflowId().value(), intent.targetId()))
                    .compose(ignored -> {
                        if (intent.correlation().reply() == null) return Future.succeededFuture(RecorderResult.empty());
                        return tx.preparedQuery("INSERT INTO reply_test_intent (instance_id, wait_id) VALUES ($1, $2)")
                                .execute(Tuple.of(
                                        intent.correlation().workflowId().value(),
                                        intent.correlation().reply().waitId()))
                                .compose(rows -> immediateReplyNode == null
                                        ? Future.succeededFuture(RecorderResult.empty())
                                        : recordParkedReply(intent, tx));
                    });
        }

        private Future<RecorderResult> recordParkedReply(WorkflowSideEffectIntent intent, SqlClient tx) {
            var id = intent.correlation().workflowId();
            var waitId = intent.correlation().reply().waitId();
            ContextHolder.Scope scope = fixture.holder()
                    .bind(
                            SecurityContext.class,
                            SecurityContexts.unauthenticated(SystemIdentities.internal("outbox reply")));
            try {
                return ReplySignalsTestBridge.relayedSignals(immediateReplyNode.engine)
                        .signalWait(
                                id,
                                waitId,
                                new Reserved("applied"),
                                SignalProvenance.outbox("inventory.reserve", 7L),
                                tx)
                        .compose(receipt -> {
                            if (receipt.outcome() != CorrelatedSignalReceipt.Outcome.RECORDED)
                                return Future.failedFuture("reply was not recorded");
                            // Simulate a previously parked delivery after real admission and queue allocation.
                            return tx.preparedQuery("UPDATE workflow_commands SET state = 'PARKED' "
                                            + "WHERE workflow_id = $1 AND kind = 'SIGNAL' AND state = 'PENDING' "
                                            + "AND target_json->>'waitId' = $2")
                                    .execute(Tuple.of(id.value(), waitId.toString()))
                                    .compose(rows -> rows.rowCount() == 1
                                            ? Future.succeededFuture(RecorderResult.empty())
                                            : Future.failedFuture("expected one recorded reply"));
                        })
                        .eventually(() -> {
                            scope.close();
                            return Future.succeededFuture();
                        });
            } catch (Throwable e) {
                scope.close();
                return Future.failedFuture(e);
            }
        }
    }
}
