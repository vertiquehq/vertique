// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.LEAK_MARKER;
import static dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.completedEntries;
import static dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.outputObject;
import static dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.outputRowOf;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.MapperSwitch;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Mode;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.OutputRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what happens when the output mapper of a definition cannot produce a value as the instance completes: the
 * instance does not complete, and what follows depends on the command that drove it to the end. A timer fails the
 * instance and a retry recovers it; a signal is rejected and left waiting; a task decision is rejected and the task
 * is open again. Each is recovered with a fixed mapper under the same version.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeclaredOutputFailureIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_declared_output_failure_it")
            .withMigration("classpath:db/migration/workflow");

    /** A bound small enough for an output to cross it; the value is the lowest the configuration accepts. */
    private static final WorkflowRecordingConfig SMALL_BOUND = new WorkflowRecordingConfig(5000L, 1024, 100);

    @Test
    @DisplayName("a mapper that throws fails a timer-driven completion, and a retry with a fixed mapper completes it")
    void throwingMapperFailsTheTimerAndRetryRecovers() {
        timerDrivenFailureThenRetry(Mode.THROW);
    }

    @Test
    @DisplayName(
            "a mapper that returns null fails a timer-driven completion, and a retry with a fixed mapper completes")
    void nullOutputFailsTheTimerAndRetryRecovers() {
        timerDrivenFailureThenRetry(Mode.NULL);
    }

    @Test
    @DisplayName("an output above the payload bound fails a timer-driven completion, and a retry recovers it")
    void oversizedOutputFailsTheTimerAndRetryRecovers() {
        timerDrivenFailureThenRetry(Mode.OVERSIZED);
    }

    @Test
    @DisplayName("an output nested deeper than 64 containers fails a timer-driven completion, and a retry recovers it")
    void tooDeepOutputFailsTheTimerAndRetryRecovers() {
        timerDrivenFailureThenRetry(Mode.TOO_DEEP);
    }

    @Test
    @DisplayName("a mapper that throws rejects the signal that would complete; the same signal later completes")
    void throwingMapperRejectsTheSignalAndLeavesTheInstanceWaiting() {
        MapperSwitch mapper = new MapperSwitch(Mode.THROW);
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                DeclaredOutputFixtures.signalThenComplete("declared-output-signal", mapper),
                List.of("approve"))) {
            // Given an instance waiting for approve, with a definition whose mapper throws
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, LEAK_MARKER + "-signal");
            JsonObject waiting = f.snapshot(id);
            assertThat(waiting.getString("status")).isEqualTo("WAITING");

            // When approve is recorded and the executor applies it
            f.record(node, id, "approve", signal("first"), "k1");
            f.idle(node, id);

            // Then the signal command is rejected, the instance waits at the same wait on the same version, and
            // nothing completed
            assertThat(f.signal(id, "k1").state()).isEqualTo("REJECTED");
            assertThat(f.snapshot(id)).isEqualTo(waiting);
            OutputRow rejected = outputRowOf(f, id);
            assertThat(rejected.status()).isEqualTo("WAITING");
            assertThat(rejected.outputJson()).isNull();
            assertThat(rejected.outputTypeName()).isNull();
            assertThat(completedEntries(f, id)).isEmpty();
            assertThat(eventsOf(f, id, WorkflowEventType.WORKFLOW_COMPLETED)).isEmpty();

            // When the mapper is fixed and approve is delivered again under a new key
            mapper.fix();
            f.record(node, id, "approve", signal("second"), "k2");
            f.idle(node, id);

            // Then the instance completes with its output, and one completion event carries it
            assertThat(f.signals(id)).extracting(CommandRow::state).containsExactly("REJECTED", "COMPLETE");
            OutputRow completed = outputRowOf(f, id);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(outputObject(completed).getString("ref")).isEqualTo(LEAK_MARKER + "-signal");
            assertThat(completed.outputTypeName()).isEqualTo(DeclaredOutputFixtures.Receipt.class.getName());
            assertThat(completedEntries(f, id)).hasSize(1);
            List<WorkflowEventIntent> events = eventsOf(f, id, WorkflowEventType.WORKFLOW_COMPLETED);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().attributes()).containsOnlyKeys("output");
        }
    }

    @Test
    @DisplayName("a mapper that throws rejects the task decision that would complete and reopens the task")
    void throwingMapperRejectsTheTaskDecisionAndReopensTheTask() {
        MapperSwitch mapper = new MapperSwitch(Mode.THROW);
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                DeclaredOutputFixtures.taskThenComplete("declared-output-task", mapper),
                List.of())) {
            TaskDriver tasks = new TaskDriver(f);
            // Given an open task whose approval completes the instance, with a mapper that throws
            Node node = f.node("engine").build();
            OpenTask task = tasks.open(node, LEAK_MARKER + "-task");
            JsonObject waiting = f.snapshot(task.id());

            // When the task is completed and the executor applies the decision
            tasks.complete(node, task, new JsonObject(), "k1");
            f.idle(node, task.id());

            // Then the completion command is rejected, the task is open again, and the instance is as it was
            assertThat(tasks.completions(task.id()))
                    .extracting(CommandRow::state)
                    .containsExactly("REJECTED");
            assertThat(tasks.statusOf(task)).isEqualTo("OPEN");
            assertThat(f.snapshot(task.id())).isEqualTo(waiting);
            assertThat(outputRowOf(f, task.id()).outputJson()).isNull();
            assertThat(completedEntries(f, task.id())).isEmpty();
            assertThat(eventsOf(f, task.id(), WorkflowEventType.WORKFLOW_COMPLETED))
                    .isEmpty();

            // When the mapper is fixed and the task is completed again under a new key
            mapper.fix();
            tasks.complete(node, task, new JsonObject(), "k2");
            f.idle(node, task.id());

            // Then the instance completes with its output
            assertThat(tasks.completions(task.id()))
                    .extracting(CommandRow::state)
                    .containsExactly("REJECTED", "COMPLETE");
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETED");
            OutputRow completed = outputRowOf(f, task.id());
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(outputObject(completed).getString("ref")).isEqualTo(LEAK_MARKER + "-task");
            assertThat(eventsOf(f, task.id(), WorkflowEventType.WORKFLOW_COMPLETED))
                    .hasSize(1);
        }
    }

    /**
     * The scenario of a timer-driven completion whose mapper cannot produce a value, with the recovery by a retry.
     * Shared by the proofs of each way a mapper can fail.
     */
    private static void timerDrivenFailureThenRetry(Mode failure) {
        MapperSwitch mapper = new MapperSwitch(failure);
        String ref = LEAK_MARKER + "-" + failure.name().toLowerCase();
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                DeclaredOutputFixtures.timerThenComplete("declared-output-timer", mapper),
                List.of())) {
            TimerFixture timers = new TimerFixture(f);
            // Given an instance waiting on a timer, a node with a small payload bound, and a mapper that fails
            Node node = f.node("engine").recordingConfig(SMALL_BOUND).build();
            WorkflowInstanceId id = f.waiting(node, ref);
            TimerRow timer = timers.only(id, "FIRE");

            // When the timer is due and the executor runs it
            timers.dueNow(timer.id());
            f.idle(node, id);

            // Then the instance failed as a deterministic failure, with no output and no completion
            OutputRow failed = outputRowOf(f, id);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType()).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(failed.outputJson()).isNull();
            assertThat(failed.outputTypeName()).isNull();
            assertThat(f.fixture.historyOf(id)).doesNotContain("COMPLETED");
            assertThat(eventsOf(f, id, WorkflowEventType.WORKFLOW_COMPLETED)).isEmpty();
            // And the failure was announced once, with an error that carries neither the value nor the cause's text
            List<WorkflowEventIntent> announced = eventsOf(f, id, WorkflowEventType.WORKFLOW_FAILED);
            assertThat(announced).hasSize(1);
            assertThat(announced.getFirst().attributes().get("errorType")).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(String.valueOf(announced.getFirst().attributes().get("errorMessage")))
                    .doesNotContain(LEAK_MARKER, "mapper echoed");
            assertThat(failed.errorMessage()).doesNotContain(LEAK_MARKER, "mapper echoed");

            // When the mapper is fixed and the instance is retried
            mapper.fix();
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
            f.idle(node, id);

            // Then the instance completed with its output, and exactly one completion event carries it
            OutputRow completed = outputRowOf(f, id);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            JsonObject output = outputObject(completed);
            assertThat(output.getString("ref")).isEqualTo(ref);
            assertThat(completed.outputTypeName()).isEqualTo(DeclaredOutputFixtures.Receipt.class.getName());
            assertThat(completedEntries(f, id)).hasSize(1);
            List<WorkflowEventIntent> events = eventsOf(f, id, WorkflowEventType.WORKFLOW_COMPLETED);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().attributes()).containsOnlyKeys("output");
            assertThat(asObject(events.getFirst().attributes().get("output")).getString("ref"))
                    .isEqualTo(ref);
            assertThat(eventsOf(f, id, WorkflowEventType.WORKFLOW_FAILED)).hasSize(1);
        }
    }

    /** The event carries the decoded object; whether it is held as a map or a JSON object, it is one value. */
    private static JsonObject asObject(Object value) {
        return value instanceof JsonObject object ? object : new JsonObject(Json.encode(value));
    }

    private static List<WorkflowEventIntent> eventsOf(SignalFixture f, WorkflowInstanceId id, WorkflowEventType type) {
        return f.fixture.workflowEvents.stream()
                .filter(event -> event.workflowId().equals(id) && event.eventType() == type)
                .toList();
    }
}
