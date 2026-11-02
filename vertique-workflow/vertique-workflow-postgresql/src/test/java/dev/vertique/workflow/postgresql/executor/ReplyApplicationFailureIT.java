// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Mode;
import dev.vertique.workflow.postgresql.executor.ReplyStepFixture.Reserved;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplyApplicationFailureIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reply_application_failure_it")
            .withMigration("classpath:db/migration/workflow");
    private static final ServiceFailure FAILURE = new ServiceFailure(ServiceFailure.Reason.REPORTED, 0);

    @Test
    void replyThatFailsToApplyParksAndRetryAppliesIt() throws Exception {
        for (String variant : List.of("reply", "failure", "unbound")) {
            try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.APPLY_FAILURE)) {
                Node node = f.node().build();
                WorkflowInstanceId id = ready(f, node);
                var before = f.fixture.instanceOf(id);
                var beforeWaitColumns = waitColumns(f, id);
                UUID wait = f.waitRecords(id).getFirst().waitId();
                node.stopExecutor();
                f.breakReducer();
                if (variant.equals("failure")) f.failWait(node, id, wait, FAILURE);
                else
                    f.signalWait(node, id, wait, variant.equals("unbound") ? "not-an-object" : new Reserved("applied"));
                node.startExecutor();
                node.awaitIdle(id);
                assertThat(f.targets(id)).doesNotContain("undo.first");
                assertParked(f, id, wait, before, beforeWaitColumns);
                assertThat(f.fixture.workflowEvents)
                        .anyMatch(e -> e.workflowId().equals(id) && e.eventType() == WorkflowEventType.WORKFLOW_FAILED);
                node.stopExecutor();
                f.mendReducer();
                if (variant.equals("unbound")) {
                    await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "UPDATE workflow_commands SET payload_json = jsonb_build_object('reference', 'applied') "
                                            + "WHERE workflow_id = $1 AND kind = 'SIGNAL' AND state = 'PARKED'")
                            .execute(Tuple.of(id.value())));
                }
                f.retry(node, id);
                node.startExecutor();
                node.awaitIdle(id);
                assertThat(states(f, id))
                        .as("variant %s, instance %s, error %s", variant, f.fixture.instanceOf(id), error(f, id))
                        .containsExactly("COMPLETE", "COMPLETE");
                assertThat(f.waitRecords(id)).isEmpty();
                var state = VertiqueJson.mapper()
                        .readValue(String.valueOf(f.fixture.instanceOf(id).stateJson()), ReplyStepFixture.State.class);
                assertThat(state.reserved()).isEqualTo(variant.equals("failure") ? "REPORTED:0" : "applied");
                assertThat(f.fixture.instanceOf(id).currentStepId())
                        .isEqualTo(variant.equals("failure") ? "failure-route" : "after");
                assertThat(f.targets(id)).doesNotContain("undo.first");
            }
        }
    }

    @Test
    void retryThatFailsAgainParksAgain() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.APPLY_FAILURE)) {
            Node node = f.node().build();
            var id = ready(f, node);
            var before = f.fixture.instanceOf(id);
            var beforeWaitColumns = waitColumns(f, id);
            UUID wait = f.waitRecords(id).getFirst().waitId();
            node.stopExecutor();
            f.breakReducer();
            f.signalWait(node, id, wait, new Reserved("once"));
            node.startExecutor();
            node.awaitIdle(id);
            assertParked(f, id, wait, before, beforeWaitColumns);
            f.retry(node, id);
            node.awaitIdle(id);
            assertParked(f, id, wait, before, beforeWaitColumns);
            assertThat(f.fixture.workflowEvents.stream()
                            .filter(e ->
                                    e.workflowId().equals(id) && e.eventType() == WorkflowEventType.WORKFLOW_FAILED))
                    .hasSize(2);
            assertThat(f.targets(id)).doesNotContain("undo.first");
        }
    }

    @Test
    void failedNextStepLeavesNothingOfTheStep() {
        for (Mode mode : List.of(Mode.NEXT_FAILURE, Mode.STEP_LIMIT)) {
            try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), mode)) {
                Node node = f.withStepCap(50).build();
                var id = ready(f, node);
                var before = f.fixture.instanceOf(id);
                var beforeWaitColumns = waitColumns(f, id);
                UUID wait = f.waitRecords(id).getFirst().waitId();
                node.stopExecutor();
                f.signalWait(node, id, wait, new Reserved("value"));
                node.startExecutor();
                node.awaitIdle(id);
                assertParked(f, id, wait, before, beforeWaitColumns);
                assertThat(f.targets(id)).containsExactlyInAnyOrder("service.first", "inventory.reserve");
                assertThat(f.intents(id)).hasSize(2);
                assertThat(f.fixture.historyTextsOf(id)).noneMatch(s -> s.contains("next-call") || s.contains("spin-"));
                assertThat(error(f, id))
                        .isEqualTo(
                                mode == Mode.STEP_LIMIT
                                        ? CommandErrors.signalStepLimit(ReplyStepFixture.REPLY, 50)
                                        : CommandErrors.signalFailure(ReplyStepFixture.REPLY));
            }
        }
    }

    @Test
    void reducerExceptionTextReachesNeitherHistoryNorTheError() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.APPLY_FAILURE)) {
            Node node = f.node().build();
            var id = ready(f, node);
            var wait = f.waitRecords(id).getFirst().waitId();
            var waitColumns = waitColumns(f, id);
            node.stopExecutor();
            f.breakReducer();
            f.failWait(node, id, wait, FAILURE);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(error(f, id)).isEqualTo(CommandErrors.signalFailure("vertique.reply.failed.reserve"));
            assertThat(waitColumns(f, id)).isEqualTo(waitColumns);
            assertThat(f.fixture.historyTextsOf(id)).noneMatch(s -> s.contains("SECRET-77"));
            assertThat(f.fixture.logged(Level.INFO))
                    .noneMatch(e -> e.getFormattedMessage().contains("SECRET-77")
                            || (e.getThrowableProxy() != null
                                    && ThrowableProxyUtil.asString(e.getThrowableProxy())
                                            .contains("SECRET-77")));
            var events = f.fixture.workflowEvents.stream()
                    .filter(e -> e.workflowId().equals(id))
                    .toList();
            assertThat(events).anyMatch(e -> e.eventType() == WorkflowEventType.WORKFLOW_FAILED);
            var emitted = VertiqueJson.mapper().valueToTree(events);
            assertThat(emitted.toString()).doesNotContain("SECRET-77");
            assertThat(emitted.findValues("reason")).isEmpty();
            var history = await(f.fixture
                            .observer()
                            .preparedQuery(
                                    "SELECT row_to_json(h)::text AS entry FROM workflow_history h WHERE workflow_id = $1 AND entry_type <> 'COMMAND_RECEIVED'")
                            .execute(Tuple.of(id.value())))
                    .stream()
                    .map(row -> row.getString("entry"))
                    .toList();
            assertThat(history).noneMatch(entry -> entry.contains("SECRET-77") || entry.contains("\"reason\""));
        }
    }

    @Test
    void failedBranchReplyAndParkedSiblingApplyOnRetry() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.FORK_APPLY_FAILURE)) {
            Node node = f.node().build();
            var id = f.startForkOfTwoCalls(node);
            node.startExecutor();
            node.awaitIdle(id);
            var a = f.branchWait(id, "a");
            var b = f.branchWait(id, "b");
            var beforeWaitColumns = waitColumns(f, id);
            node.stopExecutor();
            f.breakReducer();
            f.failWait(node, id, a.waitId(), FAILURE);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(states(f, id)).containsExactly("PARKED");
            assertThat(f.waitRecords(id)).containsExactlyInAnyOrder(a, b);
            assertThat(waitColumns(f, id)).isEqualTo(beforeWaitColumns);
            node.stopExecutor();
            f.signalWait(node, id, b.waitId(), new Reserved("sibling"));
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(states(f, id)).containsExactly("PARKED", "PARKED");
            assertThat(f.waitRecords(id)).containsExactlyInAnyOrder(a, b);
            assertThat(waitColumns(f, id)).isEqualTo(beforeWaitColumns);
            f.mendReducer();
            f.retry(node, id);
            node.awaitIdle(id);
            assertThat(states(f, id)).containsExactly("COMPLETE", "COMPLETE");
            assertThat(f.waitRecords(id)).isEmpty();
            assertThat(f.branch(id, "a").status()).isEqualTo("COMPLETED");
            assertThat(f.branch(id, "b").status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
        }
        drainStepLimitRequeuesAndAppliesInFreshSegment();
    }

    private static void drainStepLimitRequeuesAndAppliesInFreshSegment() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig(), Mode.DRAIN_STEP_LIMIT)) {
            Node node = f.withStepCap(50).singleSegments().build();
            f.parkReplyDuringDispatch(node);
            var id = f.startCall(node);
            node.runOneSegment();
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("reserve.reply");
            assertThat(states(f, id)).containsExactly("PENDING");
            int visits = f.heavyVisits.get();
            assertThat(visits).isPositive().isLessThan(40);
            assertThat(f.waitRecords(id)).hasSize(1);
            assertThat(f.openSignals(id)).isEqualTo(1);
            assertThat(f.fixture.historyTextsOf(id)).noneMatch(s -> s.contains("spin-"));
            node.runOneSegment();
            assertThat(states(f, id)).containsExactly("COMPLETE");
            assertThat(f.heavyVisits.get()).isEqualTo(visits + 40);
            assertThat(f.waitRecords(id)).isEmpty();
            assertThat(f.openSignals(id)).isZero();
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("after");
            var state = VertiqueJson.mapper()
                    .convertValue(
                            io.vertx.core.json.Json.decodeValue(
                                    String.valueOf(f.fixture.instanceOf(id).stateJson())),
                            ReplyStepFixture.State.class);
            assertThat(state.reserved()).isEqualTo("applied");
        }
    }

    private static WorkflowInstanceId ready(ReplyStepFixture f, Node node) {
        var id = f.startCall(node);
        node.startExecutor();
        node.awaitIdle(id);
        var first = f.waitRecords(id).getFirst().waitId();
        node.stopExecutor();
        f.signalWait(node, id, first, new Reserved("first"));
        node.startExecutor();
        node.awaitIdle(id);
        assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("reserve.reply");
        return id;
    }

    private static void assertParked(
            ReplyStepFixture f,
            WorkflowInstanceId id,
            UUID wait,
            ExecutorFixture.InstanceRow before,
            List<Object> beforeWaitColumns) {
        var after = f.fixture.instanceOf(id);
        assertThat(after.status()).isEqualTo("FAILED");
        assertThat(after.currentStepId()).isEqualTo(before.currentStepId());
        assertThat(after.stateJson()).isEqualTo(before.stateJson());
        assertThat(waitColumns(f, id)).isEqualTo(beforeWaitColumns);
        assertThat(f.waitRecords(id))
                .extracting(ReplyStepFixture.WaitRecord::waitId)
                .containsExactly(wait);
        assertThat(states(f, id)).containsExactly("COMPLETE", "PARKED");
        assertThat(f.fixture.historyOf(id)).contains("FAILED");
    }

    private static List<String> states(ReplyStepFixture f, WorkflowInstanceId id) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT state FROM workflow_commands WHERE workflow_id = $1 "
                                + "AND kind = 'SIGNAL' ORDER BY seq")
                        .execute(Tuple.of(id.value())))
                .stream()
                .map(row -> row.getString("state"))
                .toList();
    }

    private static List<Object> waitColumns(ReplyStepFixture f, WorkflowInstanceId id) {
        Row row = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT wait_type, wait_key, wait_aux_id FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        return java.util.Arrays.asList(row.getValue(0), row.getValue(1), row.getValue(2));
    }

    private static String error(ReplyStepFixture f, WorkflowInstanceId id) {
        Row row = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT error_message FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        return row.getString("error_message");
    }
}
