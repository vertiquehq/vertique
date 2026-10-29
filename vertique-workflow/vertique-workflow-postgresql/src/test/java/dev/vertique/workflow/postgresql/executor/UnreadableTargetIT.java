// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.RecordedMigrationIT.damageTarget;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.OrderSignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.DueDateTask;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a signal or a completion whose recorded target cannot be read is rejected, and does not fail its
 * instance: only a start, a retry, a timer and an overrun of the step cap fail an instance.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class UnreadableTargetIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_unreadable_target_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a signal whose recorded target cannot be read is rejected, frees its slot and leaves the instance")
    void signalWithAnUnreadableTargetIsRejected() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            // Given an instance waiting for paid and a recorded paid signal whose target is damaged
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "unreadable-signal");
            f.record(node, id, "paid", signal("paid"), "k1");
            assertThat(f.openSignals(id)).isEqualTo(1);
            damageTarget(f, id, "SIGNAL");

            // When the instance runs until idle
            f.idle(node, id);

            // Then the signal is rejected, its slot is free and the instance still waits for paid
            CommandRow rejected = f.signal(id, "k1");
            assertThat(rejected.state()).isEqualTo("REJECTED");
            assertThat(rejected.lastError()).matches("(?s)^[A-Z][A-Z_]*: .+");
            assertThat(f.entry(id, "COMMAND_REJECTED", rejected.id())).isNotNull();
            assertThat(f.openSignals(id)).isZero();
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.historyOf(id)).doesNotContain("FAILED");
        }
    }

    @Test
    @DisplayName(
            "a completion whose recorded target cannot be read is rejected, reopens its task and leaves the instance")
    void completionWithAnUnreadableTargetIsRejected() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();

            // Given a task reserved by a recorded completion whose target is damaged
            OpenTask task = tasks.open(node, "unreadable-completion");
            tasks.complete(node, task, new JsonObject(), "k1");
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETING");
            damageTarget(f, task.id(), "COMPLETION");

            // When the instance runs until idle
            f.idle(node, task.id());

            // Then the completion is rejected, the task is open again and the instance still waits for it
            CommandRow completion = tasks.completions(task.id()).getFirst();
            assertThat(completion.state()).isEqualTo("REJECTED");
            assertThat(f.entry(task.id(), "COMMAND_REJECTED", completion.id())).isNotNull();
            assertThat(tasks.statusOf(task)).isEqualTo("OPEN");
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("WAITING");
            assertThat(f.fixture.historyOf(task.id())).doesNotContain("FAILED");
        }
    }
}
