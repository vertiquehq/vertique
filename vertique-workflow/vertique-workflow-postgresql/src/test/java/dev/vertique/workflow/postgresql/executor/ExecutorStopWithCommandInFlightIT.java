// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that stopping the executor after the application closed the database pool under a command in flight does
 * not wait for that command's failure write to succeed: the write cannot, so the stop ends as soon as the segment has
 * ended, and the claim is released by the end of its lease.
 */
@ExtendWith(DatabaseExtension.class)
class ExecutorStopWithCommandInFlightIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_stop_in_flight_it")
            .withMigration("classpath:db/migration/workflow");

    /** Far longer than the stop may take, so that a stop that waits for the segment deadline cannot pass. */
    private static final int LEASE_SECONDS = 30;

    /** How long the stop may take; the segment deadline is more than twice as far away. */
    private static final Duration STOP_BUDGET = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("a stop after the pool closed under a command in flight ends without waiting for the segment deadline")
    void stopEndsWithoutWaitingForTheDeadlineOfAFailureThatCannotBeWritten() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a command whose segment is in flight, stalled after it holds the execution lock
            fixture.onInitialState = ref -> fixture.gate.armOnCall(1);
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .pollIntervalMs(100)
                    .concurrency(1)
                    .build();
            WorkflowInstanceId id = node.record(startOf("stop-in-flight"));
            node.startExecutor();
            await(Future.fromCompletionStage(fixture.gate.stalled()), IDLE);

            // And the application has closed the database pool, as its shutdown steps do before undeploying verticles
            await(node.pool.close(), IDLE);

            // When the executor is stopped and the segment resumes, finding its connection closed
            long started = System.nanoTime();
            Future<Void> stop = node.engine.executor().stop();
            fixture.gate.open();
            await(stop, STOP_BUDGET);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            // Then the stop ended within its budget, long before the segment deadline
            assertThat(tookMs).isLessThan(STOP_BUDGET.toMillis());

            // And the command is still claimed, with its attempt spent: its lease releases it on the next start
            CommandRow claimed = fixture.commandOf(id);
            assertThat(claimed.state()).isEqualTo("CLAIMED");
            assertThat(claimed.attempts()).isEqualTo(1);
        }
    }
}
