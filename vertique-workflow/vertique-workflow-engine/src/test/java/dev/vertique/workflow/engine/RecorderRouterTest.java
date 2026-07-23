// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecorderRouter}.
 *
 * <p>Verifies: routing to a registered recorder succeeds and delegates the call; routing to an
 * unregistered kind fails the returned {@link Future} with an {@link IllegalStateException} carrying
 * the documented message; routing to an optional kind with no registered recorder returns a
 * succeeded future with an empty result; construction with duplicate kind recorders throws
 * immediately; and independent routing when multiple kinds are registered each invokes only the
 * correct recorder.
 */
class RecorderRouterTest {

    // --- Helpers ---

    /**
     * Creates a stub {@link WorkflowSideEffectIntent} with the given kind and minimal valid data.
     *
     * @param kind the intent kind to use
     * @return a minimal intent for use in routing tests
     */
    private static WorkflowSideEffectIntent intent(IntentKind kind) {
        return new WorkflowSideEffectIntent(
                kind,
                "test.target",
                new Object(),
                Map.of(),
                WorkflowSideEffectIntent.Correlation.singlePath(
                        new WorkflowInstanceId(UUID.randomUUID()), 1L, "test-def", "test-step"));
    }

    /**
     * Creates a mock {@link WorkflowSideEffectRecorder} for the given kind.
     *
     * @param kind the kind the mock recorder should declare
     * @return a configured mock recorder
     */
    @SuppressWarnings("unchecked")
    private static WorkflowSideEffectRecorder<SqlClient> recorderFor(IntentKind kind) {
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(kind);
        return recorder;
    }

    /**
     * Creates a no-op mock {@link WorkflowReminderComposeValidator} for test purposes.
     * The validator is mocked so that no startup check runs during construction.
     *
     * @return a mocked validator
     */
    private static WorkflowReminderComposeValidator mockValidator() {
        return mock(WorkflowReminderComposeValidator.class);
    }

    // --- Empty set routing (mandatory kind) ---

    @Test
    @DisplayName("empty recorder set: routing mandatory kind fails with documented message")
    void emptyRecorderSetRoutingFailsWithDocumentedMessage() {
        RecorderRouter router = new RecorderRouter(Set.of(), Set.of(), mockValidator());
        WorkflowSideEffectIntent serviceIntent = intent(IntentKind.SERVICE);
        SqlClient tx = mock(SqlClient.class);

        Future<RecorderResult> result = router.route(serviceIntent, tx);

        assertTrue(result.failed(), "Future must be failed");
        Throwable cause = result.cause();
        assertInstanceOf(IllegalStateException.class, cause);
        assertTrue(
                cause.getMessage().startsWith("No WorkflowSideEffectRecorder registered for kind SERVICE"),
                "Message must start with the documented prefix; was: " + cause.getMessage());
    }

    // --- Optional kind: missing recorder returns empty ---

    @Test
    @DisplayName("optional kind with no recorder: routing returns succeeded empty future")
    void optionalKindMissingRecorderReturnsSucceededEmpty() {
        RecorderRouter router = new RecorderRouter(Set.of(), Set.of(IntentKind.WORKFLOW_EVENT), mockValidator());
        WorkflowSideEffectIntent eventIntent = intent(IntentKind.WORKFLOW_EVENT);
        SqlClient tx = mock(SqlClient.class);

        Future<RecorderResult> result = router.route(eventIntent, tx);

        assertTrue(result.succeeded(), "Future must succeed for optional kind with no recorder");
        assertInstanceOf(RecorderResult.Empty.class, result.result(), "Result must be empty");
    }

    // --- Optional kind: registered recorder is used ---

    @Test
    @DisplayName("optional kind with registered recorder: routing delegates to recorder")
    void optionalKindWithRecorderDelegatesToRecorder() {
        WorkflowSideEffectRecorder<SqlClient> recorder = recorderFor(IntentKind.WORKFLOW_EVENT);
        SqlClient tx = mock(SqlClient.class);
        WorkflowSideEffectIntent eventIntent = intent(IntentKind.WORKFLOW_EVENT);
        when(recorder.record(eventIntent, tx)).thenReturn(Future.succeededFuture(RecorderResult.empty()));

        RecorderRouter router =
                new RecorderRouter(Set.of(recorder), Set.of(IntentKind.WORKFLOW_EVENT), mockValidator());
        Future<RecorderResult> result = router.route(eventIntent, tx);

        assertTrue(result.succeeded(), "Future must succeed");
        verify(recorder).record(eventIntent, tx);
    }

    // --- Single recorder: correct kind ---

    @Test
    @DisplayName("single SERVICE recorder: routing SERVICE intent succeeds and delegates to recorder")
    void singleServiceRecorderRoutesServiceIntent() {
        WorkflowSideEffectRecorder<SqlClient> recorder = recorderFor(IntentKind.SERVICE);
        SqlClient tx = mock(SqlClient.class);
        WorkflowSideEffectIntent intent = intent(IntentKind.SERVICE);
        when(recorder.record(intent, tx)).thenReturn(Future.succeededFuture(RecorderResult.empty()));

        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of(), mockValidator());
        Future<RecorderResult> result = router.route(intent, tx);

        assertTrue(result.succeeded(), "Future must succeed");
        verify(recorder).record(intent, tx);
    }

    // --- Single recorder: wrong kind (not optional) ---

    @Test
    @DisplayName("single SERVICE recorder: routing DELAYED_JOB intent fails with documented message")
    void singleServiceRecorderFailsRoutingDelayedJobIntent() {
        WorkflowSideEffectRecorder<SqlClient> recorder = recorderFor(IntentKind.SERVICE);
        SqlClient tx = mock(SqlClient.class);
        WorkflowSideEffectIntent intent = intent(IntentKind.DELAYED_JOB);
        when(recorder.record(intent, tx)).thenReturn(Future.succeededFuture(RecorderResult.empty()));

        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of(), mockValidator());
        Future<RecorderResult> result = router.route(intent, tx);

        assertTrue(result.failed(), "Future must be failed");
        Throwable cause = result.cause();
        assertInstanceOf(IllegalStateException.class, cause);
        assertTrue(
                cause.getMessage().startsWith("No WorkflowSideEffectRecorder registered for kind DELAYED_JOB"),
                "Message must start with the documented prefix; was: " + cause.getMessage());
    }

    // --- Duplicate kind detection ---

    @Test
    @DisplayName("two SERVICE recorders: construction throws IllegalStateException")
    void duplicateServiceRecordersThrowsAtConstruction() {
        WorkflowSideEffectRecorder<SqlClient> recorder1 = recorderFor(IntentKind.SERVICE);
        WorkflowSideEffectRecorder<SqlClient> recorder2 = recorderFor(IntentKind.SERVICE);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new RecorderRouter(Set.of(recorder1, recorder2), Set.of(), mockValidator()));

        assertTrue(
                ex.getMessage().startsWith("Duplicate WorkflowSideEffectRecorder for kind SERVICE"),
                "Message must start with the documented prefix; was: " + ex.getMessage());
    }

    // --- Multiple kinds ---

    @Test
    @DisplayName("SERVICE and DELAYED_JOB recorders: each kind routes independently")
    void multipleKindsRouteIndependently() {
        WorkflowSideEffectRecorder<SqlClient> serviceRecorder = recorderFor(IntentKind.SERVICE);
        WorkflowSideEffectRecorder<SqlClient> delayedJobRecorder = recorderFor(IntentKind.DELAYED_JOB);
        SqlClient tx = mock(SqlClient.class);
        WorkflowSideEffectIntent serviceIntent = intent(IntentKind.SERVICE);
        WorkflowSideEffectIntent delayedJobIntent = intent(IntentKind.DELAYED_JOB);
        when(serviceRecorder.record(serviceIntent, tx)).thenReturn(Future.succeededFuture(RecorderResult.empty()));
        when(delayedJobRecorder.record(delayedJobIntent, tx))
                .thenReturn(Future.succeededFuture(RecorderResult.empty()));

        RecorderRouter router =
                new RecorderRouter(Set.of(serviceRecorder, delayedJobRecorder), Set.of(), mockValidator());

        Future<RecorderResult> serviceResult = router.route(serviceIntent, tx);
        Future<RecorderResult> delayedJobResult = router.route(delayedJobIntent, tx);

        assertTrue(serviceResult.succeeded(), "SERVICE routing must succeed");
        assertTrue(delayedJobResult.succeeded(), "DELAYED_JOB routing must succeed");
        verify(serviceRecorder).record(serviceIntent, tx);
        verify(delayedJobRecorder).record(delayedJobIntent, tx);
    }
}
