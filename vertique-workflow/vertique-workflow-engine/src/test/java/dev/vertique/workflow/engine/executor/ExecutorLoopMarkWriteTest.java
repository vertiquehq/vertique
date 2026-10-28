// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Marks;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that a failing-mark write that never ends does not hold the concurrency slot of its segment. */
class ExecutorLoopMarkWriteTest {

    private Vertx vertx;
    private ExecutorLoop loop;
    private WorkflowCommandRepository<SqlClient> commands;
    private ContextInternal context;
    private final AtomicInteger markWrites = new AtomicInteger();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = (ContextInternal) vertx.getOrCreateContext();
        commands = mock(WorkflowCommandRepository.class);
        CommandHead head = new CommandHead(
                1L, new WorkflowInstanceId(UUID.randomUUID()), "START", "default", "def", 1, "hash", false, null, null);
        when(commands.peek(anyInt())).thenReturn(Future.succeededFuture(List.of(head)));
        when(commands.findEarliestAvailabilityDelayMs()).thenReturn(Future.succeededFuture(Optional.empty()));
        when(commands.claim(any(), anyString(), anyInt(), anyInt(), any(InstanceFailureListener.class)))
                .thenReturn(Future.succeededFuture(Optional.of(new ClaimedCommand(
                        1L, head.workflowId(), "START", UUID.randomUUID(), 1, ClaimedCommand.Result.CLAIMED, false))));
        WorkflowSegments segments = mock(WorkflowSegments.class);
        Marks hung = new Marks(null, () -> {
            markWrites.incrementAndGet();
            return Promise.<Void>promise().future();
        });
        when(segments.warm(any())).thenReturn(Future.succeededFuture(Warm.of(Warm.Status.RESIDENT, hung)));
        SegmentRunner runner = mock(SegmentRunner.class);
        when(runner.run(any(), any(), anyLong(), any())).thenReturn(Future.succeededFuture());
        ExecutorEvents events = ExecutorEvents.to(Set.of());
        loop = new ExecutorLoop(
                vertx,
                context,
                new WorkflowExecutorConfig(1, 1, 100, 60),
                "test-instance",
                new CommandPeek(commands, segments, new Deferral(commands, events)),
                commands,
                runner,
                segments,
                events,
                mock(StuckReporter.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        loop.stop().toCompletionStage().toCompletableFuture().get();
        vertx.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    @DisplayName("a mark write that never ends does not keep the single slot, so the next command is claimed")
    void hungMarkWriteDoesNotHoldTheSlot() {
        // Given a loop with one slot whose every segment ends at once and whose every mark write never ends
        context.runOnContext(v -> loop.start());

        // When it runs
        // Then it claims a second command, which needs the slot of the first one to be free
        verify(commands, timeout(5_000).atLeast(2))
                .claim(any(), eq("test-instance"), anyInt(), anyInt(), any(InstanceFailureListener.class));
        verify(commands, timeout(5_000).atLeast(1)).peek(anyInt());
    }
}
