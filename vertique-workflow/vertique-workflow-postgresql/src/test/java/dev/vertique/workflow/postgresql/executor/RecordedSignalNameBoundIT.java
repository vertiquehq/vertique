// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that recording refuses a signal name, fork step id or branch id over 255 characters without a value. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordedSignalNameBoundIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_signal_name_bound_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("an over-long signal name, fork step id or branch id is refused before admission and echoed nowhere")
    void overlongNamesAreRefusedWithoutEchoingThem() {
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(), new RecordedSignalIT.OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, "names");
            String longName = "x".repeat(256);

            Throwable name = failureOf(f.recordAsync(node, id, longName, signal("p"), "k1"), IDLE);
            Throwable fork = failureOf(
                    f.caller(() -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "paid", signal("p"), "k2", longName, "b", tx))),
                    IDLE);
            Throwable branch = failureOf(
                    f.caller(() -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "paid", signal("p"), "k3", "fork", longName, tx))),
                    IDLE);

            for (Throwable refusal : List.of(name, fork, branch)) {
                assertThat(refusal).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(refusal.getMessage())
                        .doesNotContain(longName)
                        .contains(id.value().toString());
            }
            assertThat(f.signals(id)).isEmpty();
            assertThat(f.dedup(id, "k1") + f.dedup(id, "k2") + f.dedup(id, "k3"))
                    .isZero();
        }
    }
}
