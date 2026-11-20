// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.json.VertiqueJson;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.ops.ServiceFailure.Reason;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Mode;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplyFailureRouteIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reply_failure_route_it")
            .withMigration("classpath:db/migration/workflow");
    private static final ServiceFailure FAILURE = new ServiceFailure(Reason.RETRIES_EXHAUSTED, 3);

    @Test
    void failWaitTakesTheFailureRouteWithItsReducer() throws Exception {
        for (Mode mode : List.of(Mode.REDUCER, Mode.IDENTITY)) {
            try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), mode)) {
                var node = f.node().build();
                var id = f.startCall(node);
                node.startExecutor();
                node.awaitIdle(id);
                node.stopExecutor();
                String before = String.valueOf(f.fixture.instanceOf(id).stateJson());
                var waitId = f.waitRecords(id).getFirst().waitId();
                assertThat(f.failWait(node, id, waitId, FAILURE).outcome()).isEqualTo(Outcome.RECORDED);
                node.startExecutor();
                node.awaitIdle(id);
                var instance = f.fixture.instanceOf(id);
                assertThat(instance.currentStepId()).isEqualTo("failure-route");
                var state = VertiqueJson.mapper()
                        .readValue(String.valueOf(instance.stateJson()), ReplyStepFixture.State.class);
                if (mode == Mode.REDUCER) {
                    assertThat(state.reserved()).isEqualTo("RETRIES_EXHAUSTED:3");
                } else {
                    assertThat(state).isEqualTo(VertiqueJson.mapper().readValue(before, ReplyStepFixture.State.class));
                }
                assertThat(f.waitRecords(id)).isEmpty();
                assertThat(receivedNames(f, id)).containsExactly("vertique.reply.failed.reserve");
            }
        }
    }

    @Test
    void failureRecordedBeforeTheFireTimeHoldsBackADueTimeout() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.TIMED)) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            var waitId = f.waitRecords(id).getFirst().waitId();
            assertThat(f.failWait(node, id, waitId, FAILURE).outcome()).isEqualTo(Outcome.RECORDED);
            var ordering = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT s.recorded_at < t.fire_at AS before_fire FROM workflow_commands s "
                                    + "JOIN workflow_commands t ON t.workflow_id = s.workflow_id "
                                    + "WHERE s.workflow_id = $1 AND s.kind = 'SIGNAL' "
                                    + "AND t.kind = 'TIMER' AND t.timer_kind = 'TIMEOUT'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next();
            assertThat(ordering.getBoolean("before_fire")).isTrue();
            f.advancePastTimeout(id);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("failure-route");
            assertThat(f.fixture.historyOf(id)).doesNotContain("TIMEOUT");
            assertThat(f.fixture.historyOf(id)).contains("TIMER_CANCELLED");
            var timer = await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT state FROM workflow_commands WHERE workflow_id = $1 AND kind = 'TIMER' "
                                            + "AND timer_kind = 'TIMEOUT'")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next();
            assertThat(timer.getString("state")).isIn("COMPLETE", "CANCELLED", "STALE");
        }
    }

    @Test
    void parkedFailureDrainsWhenTheInstanceResumes() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.BROKEN_TIMEOUT)) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            f.makeTimeoutDue(id);
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            f.deferTimeout(id);
            var waitId = f.waitRecords(id).getFirst().waitId();
            assertThat(f.failWait(node, id, waitId, FAILURE).outcome()).isEqualTo(Outcome.RECORDED);
            node.awaitIdle(id);
            assertThat(signalStates(f, id)).containsExactly("PARKED");
            f.timeoutBroken.set(false);
            f.retry(node, id);
            node.awaitIdle(id);
            assertThat(signalStates(f, id)).containsExactly("COMPLETE");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("failure-route");
            assertThat(receivedNames(f, id)).containsExactly("vertique.reply.failed.reserve");
            assertThat(f.waitRecords(id)).isEmpty();
        }
    }

    @Test
    void failedCallIsNotCompensatedAndEarlierStepsAre() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.COMPENSATION)) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            var first = f.waitRecords(id).getFirst();
            assertThat(first.stepId()).isEqualTo("first.reply");
            assertThat(f.signalWait(node, id, first.waitId(), new ReplyStepFixture.Reserved("ok"))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            node.awaitIdle(id);
            var second = f.waitRecords(id).getFirst();
            assertThat(second.stepId()).isEqualTo("second.reply");
            assertThat(f.failWait(node, id, second.waitId(), FAILURE).outcome()).isEqualTo(Outcome.RECORDED);
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPENSATED");
            List<String> targets = new ArrayList<>();
            for (var row : await(f.fixture
                    .observer()
                    .preparedQuery("SELECT target FROM reply_test_targets WHERE instance_id = $1")
                    .execute(Tuple.of(id.value())))) targets.add(row.getString("target"));
            assertThat(targets).filteredOn("undo.first"::equals).hasSize(1);
            assertThat(targets).doesNotContain("undo.second");
        }
    }

    private static List<String> receivedNames(ReplyStepFixture f, WorkflowInstanceId id) {
        List<String> names = new ArrayList<>();
        for (var row : await(f.fixture
                .observer()
                .preparedQuery("SELECT payload_json->>'signalName' AS name FROM workflow_history "
                        + "WHERE workflow_id = $1 AND entry_type = 'SIGNAL_RECEIVED' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) names.add(row.getString("name"));
        return names;
    }

    private static List<String> signalStates(ReplyStepFixture f, WorkflowInstanceId id) {
        List<String> states = new ArrayList<>();
        for (var row : await(f.fixture
                .observer()
                .preparedQuery(
                        "SELECT state FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL' ORDER BY seq")
                .execute(Tuple.of(id.value())))) states.add(row.getString("state"));
        return states;
    }
}
