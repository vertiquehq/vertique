// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WorkflowEventSideEffectRecorder}.
 *
 * <p>Verifies that the recorder:
 * <ul>
 *   <li>Declares {@link IntentKind#WORKFLOW_EVENT} from {@link WorkflowEventSideEffectRecorder#kind()}.</li>
 *   <li>Maps a {@link WorkflowEventIntent} payload to a correctly-populated {@link OutboxEntry}
 *       with a {@link WorkflowEventEnvelope} payload on the happy path.</li>
 *   <li>Returns {@link RecorderResult#empty()} when publish succeeds.</li>
 *   <li>Propagates the failure when {@link OutboxService#publish} fails.</li>
 *   <li>Rejects intents whose {@code kind} is not {@code WORKFLOW_EVENT} without calling publish.</li>
 *   <li>Rejects intents whose payload is not a {@link WorkflowEventIntent} without calling publish.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEventSideEffectRecorderTest {

    private static final WorkflowEventOutboxBinding KAFKA_BINDING =
            new WorkflowEventOutboxBinding(DestinationType.KAFKA, "workflow-events-topic");

    @Mock
    private OutboxService outboxService;

    @Mock
    private SqlClient tx;

    private WorkflowEventSideEffectRecorder recorder;

    @BeforeEach
    void setUp() {
        // composeValidator passed as null because the recorder does not dereference it; the
        // null-for-validator pattern is established by OutboxSideEffectRecorderTest in cycle 1.
        recorder = new WorkflowEventSideEffectRecorder(outboxService, KAFKA_BINDING, null);
    }

    // --- kind() ---

    @Test
    @DisplayName("kind() returns WORKFLOW_EVENT")
    void kind_returnsWorkflowEvent() {
        assertThat(recorder.kind()).isEqualTo(IntentKind.WORKFLOW_EVENT);
    }

    // --- Happy path ---

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("publishes OutboxEntry with correct fields from WorkflowEventIntent")
        void record_publishesCorrectOutboxEntry() {
            // Arrange
            UUID instanceId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            Instant now = Instant.parse("2026-05-09T10:00:00Z");
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceId);
            WorkflowSideEffectIntent.Correlation correlation =
                    WorkflowSideEffectIntent.Correlation.singlePath(workflowId, 5L, "order-fulfillment", "review");

            WorkflowEventIntent eventIntent = new WorkflowEventIntent(
                    WorkflowEventType.TASK_CREATED,
                    workflowId,
                    "order-fulfillment",
                    1L,
                    null,
                    "BK-12345",
                    now,
                    5L,
                    taskId,
                    "wait-for-approval",
                    Map.of("priority", "high"),
                    "corr-abc",
                    "cause-xyz");

            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_EVENT, null, eventIntent, Map.of("x-tenant-id", "acme"), correlation);

            when(outboxService.publish(eq(tx), any(OutboxEntry.class))).thenReturn(Future.succeededFuture(1L));

            // Act
            Future<RecorderResult> result = recorder.record(intent, tx);

            // Assert: future succeeds with empty result
            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isEqualTo(RecorderResult.empty());

            // Assert: outbox entry fields
            ArgumentCaptor<OutboxEntry> entryCaptor = ArgumentCaptor.forClass(OutboxEntry.class);
            verify(outboxService).publish(eq(tx), entryCaptor.capture());
            OutboxEntry entry = entryCaptor.getValue();

            assertThat(entry.destinationType()).isEqualTo(DestinationType.KAFKA);
            assertThat(entry.destination()).isEqualTo("workflow-events-topic");
            assertThat(entry.aggregateType()).isEqualTo("workflow");
            assertThat(entry.aggregateId()).isEqualTo(instanceId.toString());
            assertThat(entry.eventType()).isEqualTo("TASK_CREATED");
            assertThat(entry.headers()).containsEntry("x-tenant-id", "acme");

            // Assert: envelope payload
            assertThat(entry.payload()).isInstanceOf(WorkflowEventEnvelope.class);
            WorkflowEventEnvelope envelope = (WorkflowEventEnvelope) entry.payload();

            assertThat(envelope.schemaVersion()).isEqualTo(1);
            assertThat(envelope.eventType()).isEqualTo("TASK_CREATED");
            assertThat(envelope.workflowId()).isEqualTo(instanceId);
            assertThat(envelope.definitionId()).isEqualTo("order-fulfillment");
            assertThat(envelope.definitionVersion()).isEqualTo(1L);
            assertThat(envelope.businessKey()).isEqualTo("BK-12345");
            assertThat(envelope.occurredAt()).isEqualTo(now);
            assertThat(envelope.sequence()).isEqualTo(5L);
            assertThat(envelope.taskId()).isEqualTo(taskId);
            assertThat(envelope.stepId()).isEqualTo("wait-for-approval");
            assertThat(envelope.attributes()).containsEntry("priority", "high");
            assertThat(envelope.correlationId()).isEqualTo("corr-abc");
            assertThat(envelope.causationId()).isEqualTo("cause-xyz");
        }

        @Test
        @DisplayName("null intent headers — OutboxEntry gets empty headers map")
        void record_nullHeaders_usesEmptyMap() {
            WorkflowEventIntent eventIntent = minimalEventIntent(WorkflowEventType.WORKFLOW_STARTED);
            WorkflowSideEffectIntent intent = intentWith(eventIntent, null);

            when(outboxService.publish(eq(tx), any(OutboxEntry.class))).thenReturn(Future.succeededFuture(1L));

            recorder.record(intent, tx);

            ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
            verify(outboxService).publish(eq(tx), captor.capture());
            assertThat(captor.getValue().headers()).isEmpty();
        }

        @Test
        @DisplayName("publish returns failed Future — recorder propagates the failure")
        void record_publishFails_failurePropagated() {
            WorkflowEventIntent eventIntent = minimalEventIntent(WorkflowEventType.WORKFLOW_COMPLETED);
            WorkflowSideEffectIntent intent = intentWith(eventIntent, Map.of());

            RuntimeException publishError = new RuntimeException("DB connection lost");
            when(outboxService.publish(eq(tx), any(OutboxEntry.class))).thenReturn(Future.failedFuture(publishError));

            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(publishError);
        }
    }

    // --- Defensive: wrong kind ---

    @Nested
    @DisplayName("defensive checks — wrong intent kind")
    class WrongKind {

        @Test
        @DisplayName("SERVICE kind — returns failed Future without calling publish")
        void record_serviceKind_failsFutureNoPublish() {
            WorkflowSideEffectIntent intent = intentWithKind(IntentKind.SERVICE);

            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SERVICE")
                    .hasMessageContaining("WORKFLOW_EVENT");
            verify(outboxService, never()).publish(any(), any());
        }

        @Test
        @DisplayName("WORKFLOW_TIMER kind — returns failed Future without calling publish")
        void record_timerKind_failsFutureNoPublish() {
            WorkflowSideEffectIntent intent = intentWithKind(IntentKind.WORKFLOW_TIMER);

            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class).hasMessageContaining("WORKFLOW_TIMER");
            verify(outboxService, never()).publish(any(), any());
        }
    }

    // --- Defensive: wrong payload type ---

    @Nested
    @DisplayName("defensive checks — wrong payload type")
    class WrongPayloadType {

        @Test
        @DisplayName("String payload — returns failed Future without calling publish")
        void record_stringPayload_failsFutureNoPublish() {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_EVENT, null, "not-an-event-intent", Map.of(), minimalCorrelation());

            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WorkflowEventIntent")
                    .hasMessageContaining("String");
            verify(outboxService, never()).publish(any(), any());
        }

        @Test
        @DisplayName("null payload — returns failed Future without calling publish")
        void record_nullPayload_failsFutureNoPublish() {
            WorkflowSideEffectIntent intent =
                    new WorkflowSideEffectIntent(IntentKind.WORKFLOW_EVENT, null, null, Map.of(), minimalCorrelation());

            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WorkflowEventIntent")
                    .hasMessageContaining("null");
            verify(outboxService, never()).publish(any(), any());
        }
    }

    // --- Helpers ---

    /**
     * Creates a minimal {@link WorkflowEventIntent} with the given event type and otherwise
     * arbitrary values.
     *
     * @param eventType the event type for the intent
     * @return a minimal intent
     */
    private static WorkflowEventIntent minimalEventIntent(WorkflowEventType eventType) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        return new WorkflowEventIntent(
                eventType, workflowId, "test-def", 1L, null, null, Instant.now(), 1L, null, null, null, null, null);
    }

    /**
     * Creates a minimal correlation with arbitrary values.
     *
     * @return a minimal correlation
     */
    private static WorkflowSideEffectIntent.Correlation minimalCorrelation() {
        return WorkflowSideEffectIntent.Correlation.singlePath(
                new WorkflowInstanceId(UUID.randomUUID()), 1L, "test-def", "test-step");
    }

    /**
     * Wraps a {@link WorkflowEventIntent} payload in a {@link WorkflowSideEffectIntent} with the
     * correct {@link IntentKind#WORKFLOW_EVENT} kind.
     *
     * @param eventIntent the event intent payload
     * @param headers     the headers map; may be null
     * @return a complete side-effect intent
     */
    private static WorkflowSideEffectIntent intentWith(WorkflowEventIntent eventIntent, Map<String, String> headers) {
        WorkflowSideEffectIntent.Correlation correlation = WorkflowSideEffectIntent.Correlation.singlePath(
                eventIntent.workflowId(),
                1L,
                eventIntent.definitionId(),
                eventIntent.stepId() != null ? eventIntent.stepId() : "test-step");
        return new WorkflowSideEffectIntent(IntentKind.WORKFLOW_EVENT, null, eventIntent, headers, correlation);
    }

    /**
     * Creates a {@link WorkflowSideEffectIntent} with the given kind (and no valid event intent
     * payload) for defensive-check tests.
     *
     * @param kind the intent kind to use
     * @return a side-effect intent with the given kind
     */
    private static WorkflowSideEffectIntent intentWithKind(IntentKind kind) {
        return new WorkflowSideEffectIntent(kind, null, null, Map.of(), minimalCorrelation());
    }
}
