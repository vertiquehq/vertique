// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.workflow.engine.ReplySignalsTestBridge;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.StartCommand;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;

public final class ReservedNameFixture {
    private ReservedNameFixture() {}

    public static void assertRefused(DbPoolConfig db, String path, String name) {
        try (ReplyStepFixture f = ReplyStepFixture.open(db)) {
            var node = f.node().build();
            String businessKey = UUID.randomUUID().toString();
            var id = node.record(new StartCommand(
                    ReplyStepFixture.DEFINITION,
                    f.contractName,
                    new ReplyStepFixture.Start("order"),
                    UUID.randomUUID().toString(),
                    businessKey,
                    null,
                    1L,
                    null,
                    null));
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            String waitKey = f.waitRecords(id).getFirst().correlationKey();
            long before = f.openSignals(id);
            var relayed = ReplySignalsTestBridge.relayedSignals(node.engine);
            Throwable refused =
                    catchThrowable(() -> await(f.caller(() -> node.pool.withTransaction(tx -> switch (path) {
                        case "signal" -> node.engine.signal(id, name, "payload", "key", tx);
                        case "branch" -> node.engine.signal(id, name, "payload", "key", null, null, tx);
                        case "metadata" -> node.engine.signal(id, name, "payload", "key", null, null, null, tx);
                        case "relayed" -> relayed.signal(id, name, "payload", "key", null, null, tx);
                        case "relayed-metadata" -> relayed.signal(id, name, "payload", "key", null, null, null, tx);
                        case "key" ->
                            node.engine
                                    .signalByKey(
                                            CorrelatedSignal.of(
                                                    ReplyStepFixture.Contract.class, name, waitKey, "payload", "key"),
                                            tx)
                                    .mapEmpty();
                        case "business-key" ->
                            node.engine
                                    .signalByBusinessKey(
                                            CorrelatedSignal.of(
                                                    ReplyStepFixture.Contract.class,
                                                    name,
                                                    businessKey,
                                                    "payload",
                                                    "key"),
                                            tx)
                                    .mapEmpty();
                        default -> throw new IllegalArgumentException(path);
                    }))));
            assertThat(refused).hasRootCauseInstanceOf(WorkflowInvalidPayloadException.class);
            long commands = await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getLong(0);
            assertThat(commands).isZero();
            assertThat(f.openSignals(id)).isEqualTo(before);
        }
    }

    public static void assertServiceRefused(
            DbPoolConfig db,
            java.util.function.BiConsumer<
                            dev.vertique.workflow.ops.WorkflowInstanceId, dev.vertique.workflow.ops.WorkflowInstanceId>
                    send) {
        try (ReplyStepFixture f = ReplyStepFixture.open(db)) {
            var node = f.node().build();
            var id = f.startCall(node);
            var control = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.awaitIdle(control);
            node.stopExecutor();
            assertThat(f.waitRecords(id)).hasSize(1);
            long before = f.openSignals(id);
            send.accept(id, control);
            long commands = await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getLong(0);
            assertThat(commands).isZero();
            assertThat(f.openSignals(id)).isEqualTo(before);
        }
    }
}
