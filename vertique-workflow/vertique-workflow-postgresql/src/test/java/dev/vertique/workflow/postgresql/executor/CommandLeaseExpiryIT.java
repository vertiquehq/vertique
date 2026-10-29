// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.sqlclient.Tuple;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that a release or a transient retry made after the lease ended writes nothing. */
@ExtendWith(DatabaseExtension.class)
class CommandLeaseExpiryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_lease_expiry_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a release after the lease ended writes nothing")
    void releaseAfterLeaseEndedWritesNothing() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a claimed command whose lease has ended
            PgWorkflowCommandRepository commands =
                    new PgWorkflowCommandRepository(fixture.observer(), new PgDbExceptionMapper());
            ClaimedCommand claim = claimWithExpiredLease(fixture, commands);
            WorkflowInstanceId id = claim.workflowId();
            CommandRow before = fixture.commandOf(id);

            // When the late holder releases the claim
            boolean released = await(commands.release(claim));

            // Then no row changed
            assertThat(released).isFalse();
            assertThat(fixture.commandOf(id)).isEqualTo(before);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a transient retry after the lease ended writes nothing")
    void transientRetryAfterLeaseEndedWritesNothing() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a claimed command whose lease has ended
            PgWorkflowCommandRepository commands =
                    new PgWorkflowCommandRepository(fixture.observer(), new PgDbExceptionMapper());
            ClaimedCommand claim = claimWithExpiredLease(fixture, commands);
            WorkflowInstanceId id = claim.workflowId();
            CommandRow before = fixture.commandOf(id);

            // When the late holder schedules a transient retry
            int count = await(commands.retryTransient(claim, n -> 100L, "late"));

            // Then no row changed
            assertThat(count).isZero();
            assertThat(fixture.commandOf(id)).isEqualTo(before);
        }
    }

    private static ClaimedCommand claimWithExpiredLease(ExecutorFixture fixture, PgWorkflowCommandRepository commands) {
        Node recording = fixture.node("recording").build();
        WorkflowInstanceId id = recording.record(startOf("lease-expiry"));
        var heads = await(commands.peek(10));
        assertThat(heads).hasSize(1);
        ClaimedCommand claim =
                await(commands.claim(heads.get(0), "late-holder", 60)).orElseThrow();
        assertThat(claim.result()).isEqualTo(ClaimedCommand.Result.CLAIMED);
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_commands SET lease_until = clock_timestamp() - interval '1 minute'"
                        + " WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())));
        return claim;
    }
}
