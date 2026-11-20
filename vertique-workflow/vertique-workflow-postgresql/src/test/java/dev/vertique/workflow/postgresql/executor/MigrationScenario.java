// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import dev.vertique.core.json.VertiqueJson;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MigrationScenario implements AutoCloseable {
    private final ReplyStepFixture f;
    private final ExecutorFixture.Node node;
    private final WorkflowInstanceId id;

    private MigrationScenario(DbPoolConfig db) {
        f = ReplyStepFixture.open(db, ReplyStepFixture.Mode.BROKEN_TIMEOUT);
        try {
            f.registry.register(new TargetDefinition());
            var runtime = f.registry.codeDefinitions().stream()
                    .filter(r -> r.plan().definitionId().equals(ReplyStepFixture.DEFINITION)
                            && r.plan().definitionVersion() == 2L)
                    .findFirst()
                    .orElseThrow();
            Instant now = Instant.now();
            await(f.store.upsertCode(
                    new WorkflowDefinitionRecord(
                            new DefinitionKey(WorkflowScopes.DEFAULT, ReplyStepFixture.DEFINITION, 2L),
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
                            null,
                            new JsonObject()
                                    .put(
                                            "instance",
                                            new JsonArray(List.of(
                                                    ReplyStepFixture.REPLY, "vertique.reply.failed.reserve", "other")))
                                    .put("branches", new JsonObject()),
                            null),
                    false));
            node = f.node()
                    .migrations(new DefaultWorkflowMigrationRegistry(Set.of(new Handler()), f.registry))
                    .build();
            id = f.startCall(node);
            idle();
        } catch (RuntimeException | AssertionError failure) {
            f.close();
            throw failure;
        }
    }

    public static MigrationScenario atReplyWait(DbPoolConfig db) {
        return new MigrationScenario(db);
    }

    public void migrate() {
        await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.migrate(id, 2L, tx))));
        idle();
    }

    public void failAtWait() {
        f.makeTimeoutDue(id);
        idle();
        f.deferTimeout(id);
    }

    public void retry() {
        f.timeoutBroken.set(false);
        f.retry(node, id);
        idle();
    }

    public CorrelatedSignalReceipt signal(UUID waitId, String value) {
        return f.signalWait(node, id, waitId, new ReplyStepFixture.Reserved(value));
    }

    public void apply() {
        idle();
    }

    public List<UUID> waits() {
        return f.waitRecords(id).stream()
                .map(ReplyStepFixture.WaitRecord::waitId)
                .toList();
    }

    public List<UUID> dispatches() {
        return f.intents(id);
    }

    public UUID timer() {
        return instance().getUUID("wait_aux_id");
    }

    public String timerStatus(UUID timerId) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                        .execute(Tuple.of(timerId)))
                .iterator()
                .next()
                .getString("status");
    }

    public String status() {
        return instance().getString("status");
    }

    public String step() {
        return instance().getString("current_step_id");
    }

    public long version() {
        return instance().getLong("definition_version");
    }

    public String state() {
        return String.valueOf(instance().getValue("state_json"));
    }

    public Object reserved() {
        try {
            return VertiqueJson.mapper().readValue(state(), Map.class).get("reserved");
        } catch (java.io.IOException failure) {
            throw new AssertionError("invalid workflow state", failure);
        }
    }

    private Row instance() {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT status, current_step_id, definition_version, state_json, wait_aux_id "
                                + "FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
    }

    private void idle() {
        node.startExecutor();
        try {
            node.awaitIdle(id);
        } finally {
            node.stopExecutor();
        }
    }

    @Override
    public void close() {
        f.close();
    }

    private static final class TargetDefinition
            implements WorkflowDefinition<ReplyStepFixture.State, ReplyStepFixture.Contract> {
        @Override
        public Class<ReplyStepFixture.Contract> contract() {
            return ReplyStepFixture.Contract.class;
        }

        @Override
        public Class<ReplyStepFixture.State> stateType() {
            return ReplyStepFixture.State.class;
        }

        @Override
        public String definitionId() {
            return ReplyStepFixture.DEFINITION;
        }

        @Override
        public long definitionVersion() {
            return 2L;
        }

        @Override
        public void define(WorkflowBuilder<ReplyStepFixture.State> wf) {
            wf.init(ReplyStepFixture.Start.class, start -> new ReplyStepFixture.State(start.ref(), null))
                    .initialStep("reserve");
            wf.call("reserve", "inventory.reserve", state -> state.ref())
                    .onReply(
                            ReplyStepFixture.Reserved.class,
                            (state, value) -> new ReplyStepFixture.State(state.ref(), value.reference()))
                    .toStepOnReply("after")
                    .onFailure()
                    .toStepOnFailure("done")
                    .timeoutAfter(Duration.ofHours(1))
                    .onTimeout(state -> state)
                    .toStepOnTimeout("done");
            wf.timer("after", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        }
    }

    private static final class Handler
            implements WorkflowMigrationHandler<ReplyStepFixture.State, ReplyStepFixture.State> {
        @Override
        public String definitionId() {
            return ReplyStepFixture.DEFINITION;
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
        public Class<ReplyStepFixture.State> sourceStateType() {
            return ReplyStepFixture.State.class;
        }

        @Override
        public Class<ReplyStepFixture.State> targetStateType() {
            return ReplyStepFixture.State.class;
        }

        @Override
        public MigrationResult<ReplyStepFixture.State> migrate(ReplyStepFixture.State state, MigrationContext ctx) {
            return MigrationResult.continueAt(state, "reserve.reply");
        }
    }
}
