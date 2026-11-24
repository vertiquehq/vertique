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
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.MapperSwitch;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Mode;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.OutputRow;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Receipt;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the ways a completion is reached that the other declared-output proofs do not drive: a fork branch that ends
 * in a complete step, and a completion reached at start, by a retry and by the application of a parked signal. A branch
 * completes without computing an output; the three direct paths run the mapper and fail the completion when it throws.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeclaredOutputCompletionPathsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_declared_output_paths_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a fork branch that ends in a complete step computes no output; the parent computes it once")
    void branchCompletionComputesNoOutputAndTheParentComputesItOnce() {
        AtomicInteger mapperCalls = new AtomicInteger();
        Definition fork = new Definition("declared-output-fork", wf -> {
            wf.output(Receipt.class, state -> {
                mapperCalls.incrementAndGet();
                return new Receipt(state.ref(), state.applied().size(), null);
            });
            wf.initialStep("fork")
                    .fork("fork")
                    .branch("a", "a-done")
                    .branch("b", "b-done")
                    .join("join");
            wf.complete("a-done");
            wf.complete("b-done");
            wf.join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin();
            wf.complete("done");
        });
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), fork, List.of())) {
            // Given a definition that declares an output, forks two branches that each end in a complete step, and
            // joins them into the parent's own complete step
            Node node = f.node("engine").build();

            // When an instance runs through the fork and the join
            WorkflowInstanceId id = f.waiting(node, LEAK_MARKER + "-fork");

            // Then both branch tokens completed, and the mapper ran once, at the parent's completion
            assertThat(f.scalar(
                            "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1 AND status='COMPLETED'",
                            id))
                    .isEqualTo(2);
            assertThat(f.scalar("SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1", id))
                    .isEqualTo(2);
            assertThat(mapperCalls.get()).isEqualTo(1);
            // And the parent stored the output the mapper computed, with one completion entry and one event
            OutputRow parent = outputRowOf(f, id);
            assertThat(parent.status()).isEqualTo("COMPLETED");
            assertThat(outputObject(parent).getString("ref")).isEqualTo(LEAK_MARKER + "-fork");
            assertThat(parent.outputTypeName()).isEqualTo(Receipt.class.getName());
            assertThat(completedEntries(f, id)).hasSize(1);
            List<WorkflowEventIntent> events = completionEvents(f, id);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().attributes()).containsOnlyKeys("output");
        }
    }

    @Test
    @DisplayName("a throwing mapper fails a completion reached at start; the instance is failed with no output")
    void throwingMapperFailsACompletionReachedAtStart() {
        MapperSwitch mapper = new MapperSwitch(Mode.THROW);
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                DeclaredOutputFixtures.instantComplete("declared-output-start", mapper),
                List.of())) {
            // Given a definition whose first step completes, with a mapper that throws
            Node node = f.node("engine").build();

            // When an instance is started and the executor runs the start
            WorkflowInstanceId id = await(f.caller(
                    () -> node.pool.withTransaction(tx -> node.engine.start(f.start(LEAK_MARKER + "-start"), tx))));
            f.idle(node, id);

            // Then the start command settled as a deterministic failure and the instance did not complete
            assertThat(f.fixture.commandsOf(id)).singleElement().satisfies(command -> {
                assertThat(command.kind()).isEqualTo("START");
                assertThat(command.state()).isEqualTo("FAILED");
            });
            OutputRow failed = outputRowOf(f, id);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType()).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(failed.errorMessage()).doesNotContain(LEAK_MARKER, "mapper echoed");
            assertThat(failed.outputJson()).isNull();
            assertThat(failed.outputTypeName()).isNull();
            assertThat(completedEntries(f, id)).isEmpty();
            assertThat(completionEvents(f, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("a throwing mapper fails a completion reached by a retry; the instance stays failed with no output")
    void throwingMapperFailsACompletionReachedByARetry() {
        MapperSwitch mapper = new MapperSwitch(Mode.THROW);
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                DeclaredOutputFixtures.timerThenComplete("declared-output-retry", mapper),
                List.of())) {
            TimerFixture timers = new TimerFixture(f);
            // Given an instance that failed completing after its timer, because the mapper throws
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, LEAK_MARKER + "-retry");
            timers.dueNow(timers.only(id, "FIRE").id());
            f.idle(node, id);
            assertThat(outputRowOf(f, id).status()).isEqualTo("FAILED");

            // When an operator retries it while the mapper still throws
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
            f.idle(node, id);

            // Then the retry's completion failed the same way: failed, no output, no completion, no new timer
            assertThat(f.fixture.historyOf(id)).contains("RETRIED");
            OutputRow failed = outputRowOf(f, id);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType()).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(failed.errorMessage()).doesNotContain(LEAK_MARKER, "mapper echoed");
            assertThat(failed.outputJson()).isNull();
            assertThat(failed.outputTypeName()).isNull();
            assertThat(completedEntries(f, id)).isEmpty();
            assertThat(completionEvents(f, id)).isEmpty();
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id=$1", id))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a throwing mapper rejects the parked signal whose application would complete; the instance keeps"
            + " waiting")
    void throwingMapperRejectsTheParkedSignalThatWouldComplete() {
        MapperSwitch mapper = new MapperSwitch(Mode.THROW);
        Definition sequence = new Definition("declared-output-parked", wf -> {
            wf.output(Receipt.class, mapper::apply);
            wf.initialStep("a");
            RecordedSignalIT.waitFor(wf, "a", "a", "b");
            RecordedSignalIT.waitFor(wf, "b", "b", "done");
            wf.complete("done");
        });
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), sequence, List.of("a", "b"))) {
            // Given an instance waiting for a, with b already recorded early and parked
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, LEAK_MARKER + "-parked");
            f.record(node, id, "b", signal("early"), "kb");
            f.idle(node, id);
            assertThat(f.signal(id, "kb").state()).isEqualTo("PARKED");

            // When a arrives, so the instance reaches the wait for b and applies the parked b, which would complete
            f.record(node, id, "a", signal("first"), "ka");
            f.idle(node, id);

            // Then the parked b is rejected, a stays applied, and the instance waits at b with no output
            assertThat(f.signal(id, "ka").state()).isEqualTo("COMPLETE");
            assertThat(f.signal(id, "kb").state()).isEqualTo("REJECTED");
            JsonObject snapshot = f.snapshot(id);
            assertThat(snapshot.getString("status")).isEqualTo("WAITING");
            assertThat(snapshot.getString("current_step_id")).isEqualTo("b");
            assertThat(new JsonObject(snapshot.getString("state_json")).getJsonArray("applied"))
                    .isEqualTo(new JsonArray().add("a:first"));
            OutputRow waiting = outputRowOf(f, id);
            assertThat(waiting.outputJson()).isNull();
            assertThat(waiting.outputTypeName()).isNull();
            assertThat(completedEntries(f, id)).isEmpty();
            assertThat(completionEvents(f, id)).isEmpty();

            // When the mapper is fixed and b is delivered again under a new key
            mapper.fix();
            f.record(node, id, "b", signal("second"), "kb2");
            f.idle(node, id);

            // Then the instance completes with its output
            OutputRow completed = outputRowOf(f, id);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(outputObject(completed).getString("ref")).isEqualTo(LEAK_MARKER + "-parked");
            assertThat(completionEvents(f, id)).hasSize(1);
        }
    }

    private static List<WorkflowEventIntent> completionEvents(SignalFixture f, WorkflowInstanceId id) {
        return f.fixture.workflowEvents.stream()
                .filter(event ->
                        event.workflowId().equals(id) && event.eventType() == WorkflowEventType.WORKFLOW_COMPLETED)
                .toList();
    }
}
