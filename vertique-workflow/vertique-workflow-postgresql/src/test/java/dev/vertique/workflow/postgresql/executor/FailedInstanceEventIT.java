// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an instance the executor fails on behalf of a command emits {@code WORKFLOW_FAILED} the way a fail
 * node does: once, in the transaction that fails the instance, with the reason, at the sequence of the failure's
 * history entry.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FailedInstanceEventIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_failed_instance_event_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;

    @Test
    @DisplayName("a start failed by its disposition emits WORKFLOW_FAILED once, with the reason")
    void startFailedByItsDispositionEmitsWorkflowFailed() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a start whose initial-state callback always throws
            fixture.onInitialState = ref -> {
                throw new IllegalStateException("the initial state fails");
            };
            Node node = fixture.node("engine").leaseSeconds(LEASE_SECONDS).build();
            WorkflowInstanceId id = node.record(startOf("event-d3"));

            // When the executor fails the start
            node.startExecutor();
            awaitCondition("the start to fail", Duration.ofSeconds(20), () -> fixture.commandOf(id)
                    .state()
                    .equals("FAILED"));

            // Then one WORKFLOW_FAILED carries the reason code and the sequence of the failure's history entry
            List<WorkflowEventIntent> failed = failedEventsOf(fixture, id);
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).attributes().get("errorType")).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(String.valueOf(failed.get(0).attributes().get("errorMessage")))
                    .startsWith(ReasonCodes.DETERMINISTIC_FAILURE + ": ")
                    .doesNotContain("the initial state fails");
            assertThat(fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(fixture.historyOf(id)).containsExactly("FAILED");
            assertThat(failed.get(0).sequence()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("a dead-lettered start emits WORKFLOW_FAILED once, with the attempts-exhausted reason")
    void deadLetteredStartEmitsWorkflowFailed() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a start whose every segment is abandoned as a crash would, and one attempt
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = node.record(startOf("event-dead"));
            faults.crashEverySegmentOf(id);

            // When the next claim dead-letters it
            node.startExecutor();
            awaitCondition("the start to be dead-lettered", Duration.ofSeconds(40), () -> fixture.commandOf(id)
                    .state()
                    .equals("DEAD"));

            // Then one WORKFLOW_FAILED carries the reason code, after the dead-letter entry
            List<WorkflowEventIntent> failed = failedEventsOf(fixture, id);
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).attributes().get("errorType")).isEqualTo(ReasonCodes.ATTEMPTS_EXHAUSTED);
            assertThat(fixture.historyOf(id)).containsExactly("COMMAND_DEAD_LETTERED", "FAILED");
            assertThat(failed.get(0).sequence()).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("a command settled because its instance ended emits no WORKFLOW_FAILED")
    void settlingAnEndedInstanceEmitsNoWorkflowFailed() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a start claimed once and abandoned, whose instance then ends through another command
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = node.record(startOf("event-ended"));
            faults.crashSegmentsOf(id, 1);
            node.startExecutor();
            awaitCondition("the segment to be abandoned", Duration.ofSeconds(15), () -> faults.crashes() == 1);
            node.stopExecutor();
            fixture.endInstanceThroughFixtureCommand(id);

            // When the next claim settles the command
            awaitCondition("the lease to end", Duration.ofSeconds(15), () -> fixture.leaseExpired(id));
            node.startExecutor();
            awaitCondition(
                    "the command to be settled",
                    Duration.ofSeconds(15),
                    () -> fixture.commandsOf(id).get(0).state().equals("REJECTED"));

            // Then no instance failure was reported
            assertThat(failedEventsOf(fixture, id)).isEmpty();
        }
    }

    private static List<WorkflowEventIntent> failedEventsOf(ExecutorFixture fixture, WorkflowInstanceId id) {
        return fixture.workflowEvents.stream()
                .filter(event ->
                        event.eventType() == WorkflowEventType.WORKFLOW_FAILED && id.equals(event.workflowId()))
                .toList();
    }
}
