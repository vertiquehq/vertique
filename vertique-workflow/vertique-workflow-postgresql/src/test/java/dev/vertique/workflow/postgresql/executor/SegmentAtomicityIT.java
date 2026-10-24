// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that one segment commits everything it did, or nothing: a failing recorder rolls back the intents written
 * before it, and a completion that updates no row rolls back state, intents and history.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SegmentAtomicityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_segment_atomicity_it")
            .withMigration("classpath:db/migration/workflow");

    private static final List<String> SEGMENT_ENTRIES =
            List.of("START", "COMMAND_RECEIVED", "COMMAND_COMPLETED", "SIDE_EFFECT_RECORDED");

    @Test
    @DisplayName("one segment commits both intents and the wait, or nothing")
    void oneSegmentCommitsBothIntentsAndTheWaitOrNothing() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a plan of two decisions, two dispatches and a wait, and a second instance whose second dispatch
            // is recorded by a recorder that fails after writing
            Node node = fixture.node("engine").build();
            WorkflowInstanceId healthy =
                    node.record(startOf(ExecutorFixture.ATOMIC, ExecutorFixture.AtomicContract.class, "healthy"));
            WorkflowInstanceId poisoned =
                    node.record(startOf(ExecutorFixture.ATOMIC, ExecutorFixture.AtomicContract.class, "poison-1"));

            // When both starts execute
            node.startExecutor();
            node.awaitIdle(healthy);
            awaitCondition("the poisoned segment to fail", IDLE, () -> fixture.recorderFailures.get() >= 1);
            awaitCondition("the poisoned start to be failed", IDLE, () -> fixture.commandOf(poisoned)
                    .state()
                    .equals("FAILED"));

            // Then the first instance is waiting with both intents and its segment's history
            InstanceRow waiting = fixture.instanceOf(healthy);
            assertThat(waiting.status()).isEqualTo("WAITING");
            assertThat(waiting.currentStepId()).isEqualTo("paid");
            assertThat(fixture.intentsOf(healthy)).containsExactlyInAnyOrder("charge-1", "charge-2");
            assertThat(fixture.commandOf(healthy).state()).isEqualTo("COMPLETE");
            List<String> healthyHistory = fixture.historyOf(healthy);
            assertThat(Collections.frequency(healthyHistory, "START")).isEqualTo(1);
            assertThat(Collections.frequency(healthyHistory, "COMMAND_COMPLETED"))
                    .isEqualTo(1);

            // And the second instance, whose recorders did run, has no intent in the outbox, no state and no history
            // entry of a segment; its deterministic failure failed the start and the instance
            assertThat(fixture.intentCalls.stream()
                            .filter(call -> call.workflowId().equals(poisoned.value()))
                            .map(ExecutorFixture.IntentCall::stepId))
                    .containsExactly("charge-1", "charge-2");
            assertThat(fixture.intentsOf(poisoned)).isEmpty();
            InstanceRow failed = fixture.instanceOf(poisoned);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.stateJson()).isNull();
            assertThat(failed.currentStepId()).isNull();
            assertThat(fixture.commandOf(poisoned).state()).isEqualTo("FAILED");
            assertThat(fixture.historyOf(poisoned)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);
        }
    }

    @Test
    @DisplayName("a completion that updates no row rolls back state, outbox and history")
    void completionUpdatingNoRowRollsBackStateOutboxAndHistory() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a command repository decorator whose completion reports zero rows updated
            Node node = fixture.node("engine")
                    .commands(ExecutorFixture.intercepting(
                            "complete", (call, member, arguments, real) -> ExecutorFixture.noRowUpdated(member)))
                    .build();
            WorkflowInstanceId id = node.record(startOf("order-1"));

            // When a start executes
            node.startExecutor();
            awaitCondition("the segment to reach its dispatch", IDLE, () -> !fixture.intentCalls.isEmpty());
            awaitCondition("the segment to release its lock", IDLE, () -> fixture.holdersOfX(id)
                    .isEmpty());
            ExecutorFixture.sleep(Duration.ofMillis(300).toMillis());

            // Then the instance stays pending with no state, no intent and no segment history
            InstanceRow pending = fixture.instanceOf(id);
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(pending.stateJson()).isNull();
            assertThat(pending.currentStepId()).isNull();
            assertThat(fixture.intentsOf(id)).isEmpty();
            assertThat(fixture.historyOf(id)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);

            // And the command is still claimed
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("CLAIMED");
            assertThat(command.attempts()).isEqualTo(1);
            assertThat(command.claimToken()).isNotNull();
        }
    }
}
