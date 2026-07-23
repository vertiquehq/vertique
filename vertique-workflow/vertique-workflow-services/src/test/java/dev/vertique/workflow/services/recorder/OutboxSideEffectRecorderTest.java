// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OutboxSideEffectRecorder}.
 *
 * <p>Verifies that the recorder:
 * <ul>
 *   <li>Declares {@link IntentKind#SERVICE} from {@link OutboxSideEffectRecorder#kind()}.</li>
 *   <li>Resolves the target and writes the correct {@link OutboxEntry} on the happy path.</li>
 *   <li>Fails the {@link Future} (without calling {@code publish}) when the target id is unknown.</li>
 *   <li>Fails the {@link Future} with a descriptive message for each target-shape violation:
 *       {@code @OneWay}, non-{@code Future<Void>} return type, wrong payload param count.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxSideEffectRecorderTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private ServiceTargetResolver targetResolver;

    @Mock
    private SqlClient tx;

    private OutboxSideEffectRecorder recorder;

    @BeforeEach
    void setUp() {
        // Validator passed as null because the recorder doesn't dereference it (it's only an
        // injection-side-effect dependency that forces Dagger to construct the startup validator
        // before the recorder; in this unit test the Dagger graph isn't involved).
        recorder = new OutboxSideEffectRecorder(outboxService, targetResolver, null);
    }

    @Test
    @DisplayName("kind() returns SERVICE")
    void kind_returnsService() {
        assertThat(recorder.kind()).isEqualTo(IntentKind.SERVICE);
    }

    // --- Happy path ---

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("publishes OutboxEntry with correct fields including correlation headers")
        void record_publishesCorrectOutboxEntry() {
            // Arrange
            UUID instanceId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceId);
            WorkflowSideEffectIntent.Correlation correlation = WorkflowSideEffectIntent.Correlation.singlePath(
                    workflowId, 3L, "order-fulfillment", "reserve-inventory");
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.SERVICE,
                    "integration.inventory.reserve",
                    Map.of("orderId", "abc123"),
                    Map.of("x-tenant-id", "acme"),
                    correlation);

            ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
            when(meta.oneWay()).thenReturn(false);
            doReturn(Void.class).when(meta).returnType();
            when(meta.params()).thenReturn(List.of(new ParamMeta("req", ParamSource.PAYLOAD, Object.class)));

            ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
            when(target.meta()).thenReturn(meta);
            when(target.targetId()).thenReturn("integration.inventory.reserve");
            when(target.operation()).thenReturn("reserve");

            when(targetResolver.resolve("integration.inventory.reserve")).thenReturn(target);
            when(outboxService.publish(eq(tx), any(OutboxEntry.class))).thenReturn(Future.succeededFuture(1L));

            // Act
            Future<RecorderResult> result = recorder.record(intent, tx);
            assertThat(result.succeeded()).isTrue();

            // Assert: outbox entry fields
            ArgumentCaptor<OutboxEntry> entryCaptor = ArgumentCaptor.forClass(OutboxEntry.class);
            verify(outboxService).publish(eq(tx), entryCaptor.capture());
            OutboxEntry entry = entryCaptor.getValue();

            assertThat(entry.destinationType().id()).isEqualTo("SERVICE");
            assertThat(entry.destination()).isEqualTo("integration.inventory.reserve");
            assertThat(entry.eventType()).isEqualTo("reserve");
            assertThat(entry.aggregateType()).isEqualTo("WorkflowInstance");
            assertThat(entry.aggregateId()).isEqualTo(instanceId.toString());
            assertThat(entry.payload()).isEqualTo(Map.of("orderId", "abc123"));

            // Assert: correlation headers merged with intent headers
            Map<String, String> headers = entry.headers();
            assertThat(headers).containsEntry("x-tenant-id", "acme");
            assertThat(headers).containsEntry("x-workflow-id", instanceId.toString());
            assertThat(headers).containsEntry("x-workflow-step-sequence", "3");
            assertThat(headers).containsEntry("x-workflow-definition-id", "order-fulfillment");
        }
    }

    // --- Failure: unknown target id ---

    @Nested
    @DisplayName("unknown target id")
    class UnknownTargetId {

        @Test
        @DisplayName("returns failed Future with IllegalArgumentException when resolver throws")
        void record_unknownTargetId_failsFuture() {
            when(targetResolver.resolve("unknown.target"))
                    .thenThrow(new IllegalArgumentException("No such target: unknown.target"));

            WorkflowSideEffectIntent intent = minimalIntent("unknown.target");
            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown.target");
            verify(outboxService, never()).publish(any(), any());
        }
    }

    // --- Target shape validation ---

    @Nested
    @DisplayName("target shape validation")
    class TargetShapeValidation {

        @Test
        @DisplayName("@OneWay target — fails Future with documented message including target id")
        void record_oneWayTarget_failsFuture() {
            ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
            when(meta.oneWay()).thenReturn(true);

            ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
            when(target.meta()).thenReturn(meta);
            lenient().when(target.targetId()).thenReturn("svc.my-op.do");

            when(targetResolver.resolve("svc.my-op.do")).thenReturn(target);

            WorkflowSideEffectIntent intent = minimalIntent("svc.my-op.do");
            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("svc.my-op.do")
                    .hasMessageContaining("@OneWay");
            verify(outboxService, never()).publish(any(), any());
        }

        @Test
        @DisplayName("non-Future<Void> return type — fails Future with documented message")
        void record_nonVoidReturnType_failsFuture() {
            ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
            when(meta.oneWay()).thenReturn(false);
            doReturn(String.class).when(meta).returnType();

            ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
            when(target.meta()).thenReturn(meta);
            lenient().when(target.targetId()).thenReturn("svc.orders.place");

            when(targetResolver.resolve("svc.orders.place")).thenReturn(target);

            WorkflowSideEffectIntent intent = minimalIntent("svc.orders.place");
            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("svc.orders.place")
                    .hasMessageContaining("Future<Void>")
                    .hasMessageContaining("String");
            verify(outboxService, never()).publish(any(), any());
        }

        @Test
        @DisplayName("zero payload params — fails Future with documented message including count")
        void record_zeroPayloadParams_failsFuture() {
            ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
            when(meta.oneWay()).thenReturn(false);
            doReturn(Void.class).when(meta).returnType();
            when(meta.params()).thenReturn(List.of());

            ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
            when(target.meta()).thenReturn(meta);
            lenient().when(target.targetId()).thenReturn("svc.x.op");

            when(targetResolver.resolve("svc.x.op")).thenReturn(target);

            WorkflowSideEffectIntent intent = minimalIntent("svc.x.op");
            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("svc.x.op")
                    .hasMessageContaining("exactly one payload parameter")
                    .hasMessageContaining("0");
            verify(outboxService, never()).publish(any(), any());
        }

        @Test
        @DisplayName("two payload params — fails Future with documented message including count")
        void record_twoPayloadParams_failsFuture() {
            ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
            when(meta.oneWay()).thenReturn(false);
            doReturn(Void.class).when(meta).returnType();
            List<ParamMeta> twoPayloadParams = IntStream.range(0, 2)
                    .mapToObj(i -> new ParamMeta("p" + i, ParamSource.PAYLOAD, Object.class))
                    .toList();
            when(meta.params()).thenReturn(twoPayloadParams);

            ResolvedServiceTarget target = mock(ResolvedServiceTarget.class);
            when(target.meta()).thenReturn(meta);
            lenient().when(target.targetId()).thenReturn("svc.x.op2");

            when(targetResolver.resolve("svc.x.op2")).thenReturn(target);

            WorkflowSideEffectIntent intent = minimalIntent("svc.x.op2");
            Future<RecorderResult> result = recorder.record(intent, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("svc.x.op2")
                    .hasMessageContaining("exactly one payload parameter")
                    .hasMessageContaining("2");
            verify(outboxService, never()).publish(any(), any());
        }
    }

    // --- Helpers ---

    /** Creates a minimal intent for the given target id; correlation values are arbitrary. */
    private static WorkflowSideEffectIntent minimalIntent(String targetId) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent.Correlation correlation =
                WorkflowSideEffectIntent.Correlation.singlePath(workflowId, 1L, "test-def", "test-step");
        return new WorkflowSideEffectIntent(IntentKind.SERVICE, targetId, null, Map.of(), correlation);
    }
}
