// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that a burst of wake-up hints costs a bounded number of passes and scheduling reads. */
class ExecutorLoopHintSpacingTest {

    private Vertx vertx;
    private ExecutorLoop loop;
    private WorkflowCommandRepository<SqlClient> commands;
    private ContextInternal context;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = (ContextInternal) vertx.getOrCreateContext();
        commands = mock(WorkflowCommandRepository.class);
        when(commands.peek(anyInt())).thenReturn(Future.succeededFuture(List.of()));
        when(commands.findEarliestAvailabilityDelayMs()).thenReturn(Future.succeededFuture(Optional.empty()));
        ExecutorEvents events = ExecutorEvents.to(Set.of());
        loop = new ExecutorLoop(
                vertx,
                context,
                new WorkflowExecutorConfig(1, 4, 60_000, 60),
                "test-instance",
                new CommandPeek(commands, mock(WorkflowSegments.class), new Deferral(commands, events)),
                commands,
                mock(SegmentRunner.class),
                mock(WorkflowSegments.class),
                events,
                mock(StuckReporter.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        loop.stop().toCompletionStage().toCompletableFuture().get();
        vertx.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    @DisplayName("a burst of 200 wake-ups causes a bounded number of passes and earliest-delay reads")
    void burstOfWakeUpsCausesBoundedPasses() throws Exception {
        // Given a started loop that polls only once a minute
        context.runOnContext(v -> loop.start());

        // When 200 wake-ups arrive in one burst
        context.runOnContext(v -> {
            for (int i = 0; i < 200; i++) {
                loop.wake(0);
            }
        });

        // Then the burst is answered by a pass, and the passes and reads stay few however long the burst was
        verify(commands, timeout(5_000).atLeast(1)).peek(anyInt());
        Thread.sleep(500);
        verify(commands, atLeast(1)).peek(anyInt());
        verify(commands, atMost(6)).peek(anyInt());
        verify(commands, atMost(8)).findEarliestAvailabilityDelayMs();
    }
}
