// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the correlation and causation ids a workflow event carries: the correlation id the instance captured when it
 * started, and the command the transaction is executing as the event's cause.
 */
class WorkflowEventEmitterTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @SuppressWarnings("unchecked")
    private static WorkflowEventEmitter emitterRecording(
            AtomicReference<WorkflowEventIntent> sink, RecorderRouter[] out) {
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.WORKFLOW_EVENT);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            WorkflowSideEffectIntent intent = invocation.getArgument(0);
            sink.set((WorkflowEventIntent) intent.payload());
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        out[0] = router;
        return new WorkflowEventEmitter(router, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WorkflowInstance instance(DurableMetadata metadata) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                "def",
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "step",
                WaitType.SIGNAL,
                "go",
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                metadata);
    }

    private static DurableMetadata correlated(String value) {
        return DurableMetadata.of(
                "correlation",
                new JsonObject()
                        .put("schemaVersion", 1)
                        .put(
                                "correlationId",
                                new JsonObject().put("value", value).put("source", "test")));
    }

    private static SegmentCommand command(long id, WorkflowInstanceId instance) {
        return new SegmentCommand(
                id,
                instance,
                "SIGNAL",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                WorkflowAttribution.system("test"));
    }

    @Test
    @DisplayName("an event carries the correlation id the instance captured and the command its transaction executes")
    void eventCarriesTheCapturedCorrelationIdAndTheExecutingCommand() {
        // Given an instance that captured a correlation id and a transaction that executes command 42
        AtomicReference<WorkflowEventIntent> sink = new AtomicReference<>();
        RecorderRouter[] router = new RecorderRouter[1];
        WorkflowEventEmitter emitter = emitterRecording(sink, router);
        WorkflowInstance inst = instance(correlated("corr-7"));
        SqlClient tx = mock(SqlClient.class);

        // When an event is emitted while the command executes
        router[0].executing(
                command(42L, inst.id()),
                tx,
                () -> emitter.emitEvent(inst, WorkflowEventType.WORKFLOW_STARTED, 3L, null, "step", Map.of(), tx));

        // Then the event names the captured correlation and the command as its cause
        assertThat(sink.get().correlationId()).isEqualTo("corr-7");
        assertThat(sink.get().causationId()).isEqualTo("42");
    }

    @Test
    @DisplayName("an event written outside a command's segment has no causation id; an instance that captured no"
            + " correlation has no correlation id")
    void eventOutsideACommandHasNoCausationAndAnInstanceWithoutCorrelationHasNone() {
        // Given an instance without a captured correlation and a transaction that executes no command
        AtomicReference<WorkflowEventIntent> sink = new AtomicReference<>();
        RecorderRouter[] router = new RecorderRouter[1];
        WorkflowEventEmitter emitter = emitterRecording(sink, router);
        WorkflowInstance inst = instance(DurableMetadata.empty());

        // When an event is emitted
        emitter.emitEvent(inst, WorkflowEventType.WORKFLOW_CANCELLED, 4L, null, null, Map.of(), mock(SqlClient.class));

        // Then neither id is set
        assertThat(sink.get().correlationId()).isNull();
        assertThat(sink.get().causationId()).isNull();
    }

    @Test
    @DisplayName("a nested command, such as a drained parked signal, is the cause of its own events and the outer"
            + " command is restored afterwards")
    void nestedCommandIsTheCauseOfItsOwnEvents() {
        // Given a transaction that executes command 1 and, nested in it, command 2
        AtomicReference<WorkflowEventIntent> sink = new AtomicReference<>();
        RecorderRouter[] router = new RecorderRouter[1];
        WorkflowEventEmitter emitter = emitterRecording(sink, router);
        WorkflowInstance inst = instance(correlated("corr-9"));
        SqlClient tx = mock(SqlClient.class);

        router[0].executing(command(1L, inst.id()), tx, () -> {
            // When the nested command emits, and then the outer one does
            return router[0]
                    .executing(
                            command(2L, inst.id()),
                            tx,
                            () -> emitter.emitEvent(
                                    inst, WorkflowEventType.TASK_CREATED, 5L, UUID.randomUUID(), "step", Map.of(), tx))
                    .compose(done -> {
                        assertThat(sink.get().causationId()).isEqualTo("2");
                        return emitter.emitEvent(
                                inst, WorkflowEventType.TASK_COMPLETED, 6L, UUID.randomUUID(), "step", Map.of(), tx);
                    });
        });

        // Then the outer command's event names the outer command
        assertThat(sink.get().causationId()).isEqualTo("1");
    }
}
