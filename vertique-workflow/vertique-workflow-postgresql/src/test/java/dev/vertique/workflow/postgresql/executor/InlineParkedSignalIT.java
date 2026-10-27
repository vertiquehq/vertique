// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a parked signal is applied when a path that drives its instance or branch into the wait the signal
 * addresses (a task completion, a timer fire, a branch task completion) reaches that wait.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class InlineParkedSignalIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_inline_parked_signal_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a task completion that reaches the wait applies the signal parked for it")
    void taskCompletionAppliesTheParkedSignal() {
        Definition review = new Definition("inline-task", wf -> {
            wf.initialStep("review");
            wf.task("review")
                    .assignToRole("reviewers")
                    .decision("approve", Void.class)
                    .onDecision((state, payload) -> state)
                    .toStep("ship")
                    .build();
            waitFor(wf, "ship", "shipped", "done");
            wf.complete("done");
        });
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), review, List.of("shipped"))) {
            Node node = f.node("engine").build();
            // Given an instance at its open task and a signal for the later wait, parked
            WorkflowInstanceId id = f.waiting(node, "task-run");
            f.record(node, id, "shipped", signal("early"), "early");
            f.idle(node, id);
            assertThat(f.signal(id, "early").state()).isEqualTo("PARKED");

            // When the task is completed and the executor applies the recorded completion
            UUID task = await(node.pool
                    .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                    .execute(Tuple.of(id.value()))
                    .map(rows -> rows.iterator().next().getUUID("task_id")));
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.taskCompleted(
                    new TaskCompletionCommand(task, "approve", null, "approve-1", new WorkflowActor.User("u"), null),
                    tx))));
            f.idle(node, id);

            // Then the parked signal was applied in that segment and the instance went past the wait
            CommandRow applied = f.signal(id, "early");
            assertThat(applied.state()).isEqualTo("COMPLETE");
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
            assertThat(f.history(id))
                    .anyMatch(h -> h.type().equals("SIGNAL_APPLIED")
                            && Long.valueOf(applied.id()).equals(h.commandId()));
        }
    }

    @Test
    @DisplayName("a timer fire that reaches the wait applies the signal parked for it")
    void timerFireAppliesTheParkedSignal() {
        Definition pause = new Definition("inline-timer", wf -> {
            wf.initialStep("pause");
            wf.timer("pause", Duration.ofMillis(500)).toStep("ship");
            waitFor(wf, "ship", "shipped", "done");
            wf.complete("done");
        });
        UUID[] timer = new UUID[1];
        PgTimerStore timers = new PgTimerStore(new PgDbExceptionMapper());
        WorkflowSideEffectRecorder<SqlClient> timerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                timer[0] = UUID.randomUUID();
                TimerRecord record = new TimerRecord(
                        timer[0],
                        intent.correlation().workflowId(),
                        intent.targetId(),
                        Instant.now().plusSeconds(60),
                        TimerStatus.SCHEDULED,
                        UUID.randomUUID(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        TimerPurpose.STANDALONE,
                        null,
                        null,
                        null,
                        null,
                        DurableMetadata.empty());
                return timers.insertScheduled(record, tx).map(v -> RecorderResult.ofTimer(timer[0]));
            }
        };
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), pause, List.of("shipped"))) {
            Node node = f.node("engine").recorders(f.intents, timerRecorder).build();
            // Given an instance waiting for its timer and a signal for the later wait, parked
            WorkflowInstanceId id = f.waiting(node, "timer-run");
            f.record(node, id, "shipped", signal("early"), "early");
            f.idle(node, id);
            assertThat(f.signal(id, "early").state()).isEqualTo("PARKED");

            // When the timer fires on the inline path, with no executor running
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.timerFired(id, timer[0], tx))));

            // Then the parked signal was applied in that transaction and the instance went past the wait
            assertThat(f.signal(id, "early").state()).isEqualTo("COMPLETE");
            assertThat(f.snapshot(id).getString("status")).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a branch task completion that reaches the branch's wait applies the signal parked for that branch")
    void branchTaskCompletionAppliesTheParkedSignal() {
        Definition fork = new Definition("inline-branch", wf -> {
            wf.initialStep("fork")
                    .fork("fork")
                    .branch("a", "a-task", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "b-wait", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.task("a-task")
                    .assignToRole("reviewers")
                    .decision("approve", Void.class)
                    .onDecision((state, payload) -> state)
                    .toStep("a-ship")
                    .build();
            waitFor(wf, "a-ship", "shipped", "a-done");
            wf.complete("a-done");
            waitFor(wf, "b-wait", "note", "b-done");
            wf.complete("b-done");
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin();
            wf.complete("done");
        });
        JsonObject branches = new JsonObject()
                .put("fork/a", new JsonArray().add("shipped"))
                .put("fork/b", new JsonArray().add("note"));
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), fork, List.of(), branches)) {
            Node node = f.node("engine").build();
            // Given a branch at its open task and a signal for the branch's later wait, parked
            WorkflowInstanceId id = f.waiting(node, "branch-run");
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "shipped", signal("early"), "early", "fork", "a", tx))));
            f.idle(node, id);
            assertThat(f.signal(id, "early").state()).isEqualTo("PARKED");

            // When the branch's task is completed and the executor applies the recorded completion
            UUID task = await(node.pool
                    .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                    .execute(Tuple.of(id.value()))
                    .map(rows -> rows.iterator().next().getUUID("task_id")));
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.taskCompleted(
                    new TaskCompletionCommand(task, "approve", null, "approve-1", new WorkflowActor.User("u"), null),
                    tx))));
            f.idle(node, id);

            // Then the parked signal was applied and the branch completed, while the other branch still holds the join
            assertThat(f.signal(id, "early").state()).isEqualTo("COMPLETE");
            assertThat(f.scalar(
                            "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1 AND branch_id='a' AND"
                                    + " status='COMPLETED'",
                            id))
                    .isEqualTo(1);
            assertThat(f.snapshot(id).getString("status")).isNotEqualTo("COMPLETED");
        }
    }
}
