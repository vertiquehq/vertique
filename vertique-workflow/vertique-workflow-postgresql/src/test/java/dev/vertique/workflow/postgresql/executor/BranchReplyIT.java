// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Mode;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Reserved;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BranchReplyIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_reply_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void replyInABranchResumesOnlyThatBranch() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK)) {
            AtomicReference<long[]> beforeCommit = new AtomicReference<>();
            var node = f.node()
                    .leaseSeconds(5)
                    .commands(ExecutorFixture.intercepting("complete", (call, method, args, real) -> {
                        if (call != 1) return real.call();
                        return f.fixture
                                .observer()
                                .query("SELECT (SELECT count(*) FROM workflow_signal_waits) AS waits, "
                                        + "(SELECT count(*) FROM reply_test_intent) AS intents, "
                                        + "(SELECT count(*) FROM workflow_branch_tokens) AS branches")
                                .execute()
                                .map(rows -> {
                                    Row row = rows.iterator().next();
                                    beforeCommit.set(new long[] {
                                        row.getLong("waits"), row.getLong("intents"), row.getLong("branches")
                                    });
                                    return 0;
                                });
                    }))
                    .build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(beforeCommit.get()).containsExactly(0L, 0L, 0L);
            assertThat(f.waitRecords(id)).hasSize(2);
            assertThat(f.waitRecords(id))
                    .extracting(ReplyStepFixture.WaitRecord::branchId)
                    .containsExactlyInAnyOrder("a", "b");
            var a = f.branchWait(id, "a");
            var b = f.branchWait(id, "b");
            assertThat(a.forkStepId()).isEqualTo("fork");
            assertThat(a.branchId()).isEqualTo("a");
            assertThat(a.stepId()).isEqualTo("first.reply");
            assertThat(a.signalName()).isEqualTo("vertique.reply.ok.first");
            assertThat(a.contractName()).isEqualTo(f.contractName);
            assertThat(a.correlationKey()).isEqualTo(a.waitId().toString());
            assertThat(b.forkStepId()).isEqualTo("fork");
            assertThat(b.branchId()).isEqualTo("b");
            assertThat(f.intents(id)).containsExactlyInAnyOrder(a.waitId(), b.waitId());
            var bBefore = f.branch(id, "b");
            node.stopExecutor();
            assertThat(f.signalWait(node, id, a.waitId(), new Reserved("reserved-a"))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            JsonObject target = (JsonObject) await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT target_json FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getValue("target_json");
            assertThat(target.getString("forkStepId")).isEqualTo("fork");
            assertThat(target.getString("branchId")).isEqualTo("a");
            assertThat(target.getString("waitId")).isEqualTo(a.waitId().toString());
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.branch(id, "a")).isEqualTo(new ReplyStepFixture.Branch("COMPLETED", "a-done"));
            assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson())).contains("reserved-a");
            assertThat(f.branch(id, "b")).isEqualTo(bBefore);
            assertThat(f.branch(id, "b")).isEqualTo(new ReplyStepFixture.Branch("WAITING", "second.reply"));
            assertThat(f.waitRecords(id)).containsExactly(b);
        }
    }

    @Test
    void failureRoutesOnlyThatBranch() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK)) {
            var node = f.node().build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            var a = f.branchWait(id, "a");
            var b = f.branchWait(id, "b");
            var aBefore = f.branch(id, "a");
            node.stopExecutor();
            assertThat(f.failWait(node, id, b.waitId(), new ServiceFailure(ServiceFailure.Reason.REPORTED, 0))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.branch(id, "b")).isEqualTo(new ReplyStepFixture.Branch("COMPLETED", "b-failure"));
            assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson())).contains("REPORTED:0");
            assertThat(f.branch(id, "a")).isEqualTo(aBefore);
            assertThat(f.waitRecords(id)).containsExactly(a);
        }
    }

    @Test
    void failureRecordedBeforeFireHoldsBackDueBranchTimeout() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK_LOOP)) {
            var node = f.node().build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            var wait = f.branchWait(id, "a");
            assertThat(f.failWait(node, id, wait.waitId(), new ServiceFailure(ServiceFailure.Reason.REPORTED, 0))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            var ordering = await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT s.recorded_at < t.fire_at AS before_fire FROM workflow_commands s JOIN workflow_commands t ON t.workflow_id = s.workflow_id WHERE s.workflow_id = $1 AND s.kind = 'SIGNAL' AND t.kind = 'TIMER' AND t.timer_kind = 'TIMEOUT'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next();
            assertThat(ordering.getBoolean("before_fire")).isTrue();
            f.expireWait(id, "a");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.branch(id, "a")).isEqualTo(new ReplyStepFixture.Branch("COMPLETED", "a-failure"));
        }
    }

    @Test
    void reEnteredCallDispatchesAnewAndItsReplyResumes() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK_LOOP)) {
            var node = f.node().build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            UUID first = f.branchWait(id, "a").waitId();
            var sibling = f.branchWait(id, "b");
            node.stopExecutor();
            f.expireWait(id, "a");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.intents(id)).hasSize(3);
            UUID second = f.branchWait(id, "a").waitId();
            assertThat(second).isNotEqualTo(first);
            assertThat(f.intents(id)).contains(first, second, sibling.waitId()).doesNotHaveDuplicates();
            assertThat(f.targets(id)).containsExactly("service.first", "service.first", "service.second");
            node.stopExecutor();
            assertThat(f.signalWait(node, id, second, new Reserved("second-pass"))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            assertThatThrownBy(() -> f.signalWait(node, id, first, new Reserved("stale")))
                    .hasRootCauseInstanceOf(WorkflowSignalNoMatchException.class);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.branch(id, "a")).isEqualTo(new ReplyStepFixture.Branch("COMPLETED", "a-done"));
            assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson()))
                    .contains("second-pass")
                    .doesNotContain("stale");
            assertThat(f.branchWait(id, "b")).isEqualTo(sibling);
        }
    }

    @Test
    void failedForkCompensatesItsFailedBranchCall() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK_FAILURE)) {
            var node = f.node().build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            var a = f.branchWait(id, "a");
            assertThat(f.targets(id)).containsExactly("service.first", "service.second");
            node.stopExecutor();
            assertThat(f.failWait(node, id, a.waitId(), new ServiceFailure(ServiceFailure.Reason.REPORTED, 0))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.targets(id)).containsExactly("service.first", "service.second", "undo.first");
        }
    }
}
