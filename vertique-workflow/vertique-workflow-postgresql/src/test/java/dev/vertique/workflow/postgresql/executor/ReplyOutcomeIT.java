// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.workflow.engine.ReplySignalsTestBridge;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.ops.ServiceFailure.Reason;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Mode;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplyOutcomeIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reply_outcome_it")
            .withMigration("classpath:db/migration/workflow");
    private static final ServiceFailure FAILURE = new ServiceFailure(Reason.REPORTED, 1);
    private static final String SECRET = "private-payload-marker";

    enum Closer {
        TIMEOUT,
        CANCEL,
        UNKNOWN
    }

    @ParameterizedTest
    @EnumSource(Closer.class)
    void noOpenRecordAnswersNoMatchAndWritesNothing(Closer closer) {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.TIMED)) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            UUID waitId = f.waitRecords(id).getFirst().waitId();
            if (closer == Closer.TIMEOUT) {
                f.makeTimeoutDue(id);
                node.startExecutor();
                node.awaitIdle(id);
                node.stopExecutor();
                assertThat(f.waitRecords(id)).isEmpty();
            } else if (closer == Closer.CANCEL) {
                await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, "cancel", tx))));
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
            } else {
                waitId = UUID.randomUUID();
            }
            UUID target = waitId;
            long before = f.openSignals(id);
            Throwable success =
                    catchThrowable(() -> f.signalWait(node, id, target, new ReplyStepFixture.Reserved(SECRET)));
            Throwable failure = catchThrowable(() -> f.failWait(node, id, target, FAILURE));
            assertThat(count(f, id, "workflow_commands", "kind = 'SIGNAL'")).isZero();
            assertThat(count(f, id, "workflow_dedup", "kind = 'correlated'")).isZero();
            assertThat(f.openSignals(id)).isEqualTo(before);
            for (Throwable thrown : new Throwable[] {success, failure}) {
                assertThat(thrown).hasRootCauseInstanceOf(WorkflowSignalNoMatchException.class);
                assertThat(thrown.getCause().getMessage()).doesNotContain(target.toString(), SECRET);
                Throwable root = thrown;
                while (root.getCause() != null) root = root.getCause();
                var noMatch = (WorkflowSignalNoMatchException) root;
                assertThat(noMatch.contractName()).isEqualTo("vertique.reply.");
                assertThat(noMatch.signalName()).isEqualTo("vertique.reply.");
            }
        }
    }

    @Test
    void endedInstanceWithStrayWaitReturnsSanitizedNoMatch() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            var wait = f.waitRecords(id).getFirst();
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, "cancel", tx))));
            await(f.fixture
                    .observer()
                    .preparedQuery(
                            "INSERT INTO workflow_signal_waits (wait_id, instance_id, step_id, fork_step_id, branch_id, scope_key, contract_name, definition_id, signal_name, correlation_key, created_at) VALUES ($1, $2, $3, '', '', 'default', $4, $5, $6, $7, clock_timestamp())")
                    .execute(Tuple.of(
                            wait.waitId(),
                            id.value(),
                            wait.stepId(),
                            wait.contractName(),
                            ReplyStepFixture.DEFINITION,
                            wait.signalName(),
                            wait.correlationKey())));
            Throwable thrown =
                    catchThrowable(() -> f.signalWait(node, id, wait.waitId(), new ReplyStepFixture.Reserved(SECRET)));
            assertThat(thrown).hasRootCauseInstanceOf(WorkflowSignalNoMatchException.class);
            Throwable root = thrown;
            while (root.getCause() != null) root = root.getCause();
            var noMatch = (WorkflowSignalNoMatchException) root;
            assertThat(noMatch.contractName()).isEqualTo("vertique.reply.");
            assertThat(noMatch.signalName()).isEqualTo("vertique.reply.");
            assertThat(count(f, id, "workflow_commands", "kind = 'SIGNAL'")).isZero();
        }
    }

    @Test
    void anotherInstancesWaitIdRecordsNothing() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var a = f.startCall(node);
            var b = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(a);
            node.awaitIdle(b);
            node.stopExecutor();
            var wait = f.waitRecords(b).getFirst();
            assertThat(catchThrowable(
                            () -> f.signalWait(node, a, wait.waitId(), new ReplyStepFixture.Reserved(SECRET))))
                    .hasRootCauseInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(count(f, a, "workflow_commands", "kind = 'SIGNAL'")).isZero();
            assertThat(count(f, b, "workflow_commands", "kind = 'SIGNAL'")).isZero();
            assertThat(f.waitRecords(b)).containsExactly(wait);
        }
    }

    @Test
    void repeatAfterTheRecordClosedAnswersDuplicate() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            var waitId = f.waitRecords(id).getFirst().waitId();
            assertThat(f.signalWait(node, id, waitId, new ReplyStepFixture.Reserved("ok"))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            assertThat(f.waitRecords(id)).isEmpty();
            assertThat(f.signalWait(node, id, waitId, new ReplyStepFixture.Reserved("again"))
                            .outcome())
                    .isEqualTo(Outcome.DUPLICATE);
            assertThat(f.failWait(node, id, waitId, FAILURE).outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(count(f, id, "workflow_commands", "kind = 'SIGNAL'")).isEqualTo(1);
            assertThat(count(f, id, "workflow_dedup", "kind = 'correlated'")).isEqualTo(1);
        }
    }

    @Test
    void payloadOverTheBoundFailsBeforeAnyWrite() throws Exception {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            var waitId = f.waitRecords(id).getFirst().waitId();
            String payload = "x".repeat(262143);
            assertThat(VertiqueJson.mapper().writeValueAsBytes(payload)).hasSize(262145);
            long before = f.openSignals(id);
            var result = await(f.caller(() -> {
                ContextHolder.Scope scope = f.fixture
                        .holder()
                        .bind(
                                SecurityContext.class,
                                SecurityContexts.unauthenticated(SystemIdentities.internal("outbox reply")));
                return node.pool
                        .withTransaction(tx -> {
                            var relayed = ReplySignalsTestBridge.relayedSignals(node.engine);
                            return relayed.signalWait(
                                            id, waitId, payload, SignalProvenance.outbox("inventory.reserve", 7L), tx)
                                    .compose(ignored -> Future.<Outcome>failedFuture(
                                            new AssertionError("oversized payload accepted")))
                                    .recover(error -> {
                                        assertThat(error).isInstanceOf(WorkflowInvalidPayloadException.class);
                                        return tx.preparedQuery(
                                                        "SELECT open_signals FROM workflow_command_queues WHERE workflow_id = $1")
                                                .execute(Tuple.of(id.value()))
                                                .compose(rows -> {
                                                    assertThat(rows.iterator()
                                                                    .next()
                                                                    .getInteger(0)
                                                                    .longValue())
                                                            .isEqualTo(before);
                                                    return tx.preparedQuery(
                                                                    "SELECT count(*) FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                                                            .execute(Tuple.of(id.value()));
                                                })
                                                .compose(rows -> {
                                                    assertThat(rows.iterator()
                                                                    .next()
                                                                    .getLong(0))
                                                            .isZero();
                                                    return relayed.failWait(
                                                            id,
                                                            waitId,
                                                            FAILURE,
                                                            SignalProvenance.outbox("inventory.reserve", 7L),
                                                            tx);
                                                })
                                                .map(receipt -> receipt.outcome());
                                    });
                        })
                        .eventually(() -> {
                            scope.close();
                            return Future.succeededFuture();
                        });
            }));
            assertThat(result).isEqualTo(Outcome.RECORDED);
            assertThat(count(f, id, "workflow_commands", "kind = 'SIGNAL'")).isEqualTo(1);
            assertThat(count(f, id, "workflow_dedup", "kind = 'correlated'")).isEqualTo(1);
            assertThat(f.openSignals(id)).isEqualTo(before + 1);
        }
    }

    private static long count(ReplyStepFixture f, WorkflowInstanceId id, String table, String condition) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT count(*) FROM " + table + " WHERE workflow_id = $1 AND " + condition)
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
    }
}
