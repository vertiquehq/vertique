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
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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

    record WaitRecord(UUID waitId, String stepId, String contractName, String signalName, String correlationKey) {}

    final ExecutorFixture fixture;
    final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
    final PgWorkflowDefinitionRegistryStore store;
    final String contractName;

    private ReplyStepFixture(DbPoolConfig db) {
        fixture = ExecutorFixture.open(db);
        try {
            store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            registry.register(new Definition());
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
                                            new JsonArray(List.of(REPLY, "vertique.reply.failed.reserve", "other")))
                                    .put("branches", new JsonObject()),
                            null),
                    true));
            await(fixture.observer()
                    .query(
                            "CREATE TABLE IF NOT EXISTS reply_test_intent (instance_id UUID NOT NULL, wait_id UUID NOT NULL)")
                    .execute());
            await(fixture.observer().query("TRUNCATE reply_test_intent").execute());
        } catch (RuntimeException | AssertionError e) {
            fixture.close();
            throw e;
        }
    }

    static ReplyStepFixture open(DbPoolConfig db) {
        return new ReplyStepFixture(db);
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
                        "SELECT wait_id, step_id, contract_name, signal_name, correlation_key FROM workflow_signal_waits WHERE instance_id = $1")
                .execute(Tuple.of(id.value())))) {
            result.add(new WaitRecord(
                    row.getUUID("wait_id"),
                    row.getString("step_id"),
                    row.getString("contract_name"),
                    row.getString("signal_name"),
                    row.getString("correlation_key")));
        }
        return result;
    }

    List<UUID> intents(WorkflowInstanceId id) {
        List<UUID> result = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT wait_id FROM reply_test_intent WHERE instance_id = $1")
                .execute(Tuple.of(id.value())))) result.add(row.getUUID("wait_id"));
        return result;
    }

    CorrelatedSignalReceipt signalWait(Node node, WorkflowInstanceId id, UUID waitId, Reserved value) {
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
    }

    private static final class Recorder implements WorkflowSideEffectRecorder<SqlClient> {
        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            return tx.preparedQuery("INSERT INTO reply_test_intent (instance_id, wait_id) VALUES ($1, $2)")
                    .execute(Tuple.of(
                            intent.correlation().workflowId().value(),
                            intent.correlation().reply().waitId()))
                    .map(ignored -> RecorderResult.empty());
        }
    }
}
