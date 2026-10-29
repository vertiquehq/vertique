// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the seam through which the executor has the engine announce the history of a segment. */
class SegmentsAnnouncingTest {

    private final WorkflowInstanceId instance = new WorkflowInstanceId(UUID.randomUUID());
    private final SqlClient tx = mock(SqlClient.class);

    @Test
    @DisplayName("the default only runs the work")
    void defaultRunsTheWork() {
        WorkflowSegments segments = new WorkflowSegments() {
            @Override
            public WorkflowRegistry registry() {
                return mock(WorkflowRegistry.class);
            }

            @Override
            public Future<Outcome> run(
                    dev.vertique.workflow.engine.spi.CommandHead head,
                    dev.vertique.workflow.engine.spi.ClaimedCommand claim,
                    dev.vertique.workflow.engine.spi.SegmentCommand command,
                    SqlClient tx) {
                return Future.succeededFuture(Outcome.APPLIED);
            }
        };
        AtomicInteger runs = new AtomicInteger();

        Future<String> result = segments.announcing(instance, tx, () -> {
            runs.incrementAndGet();
            return Future.succeededFuture("done");
        });

        assertThat(result.result()).isEqualTo("done");
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("the decorator that holds plans hands the announcement to the engine's segments")
    void holdingSegmentsForward() {
        WorkflowSegments engine = mock(WorkflowSegments.class);
        HoldingSegments holding = new HoldingSegments(() -> engine, () -> mock(PlanCache.class));
        java.util.function.Supplier<Future<String>> work = () -> Future.succeededFuture("done");

        holding.announcing(instance, tx, work);

        verify(engine).announcing(instance, tx, work);
    }
}
