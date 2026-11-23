// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the attribution headers the router stamps on a segment's intent from the command the segment executes: the
 * command's actor, with every application-set header whose name starts with the workflow prefix removed first, in any
 * letter case.
 */
class RecorderRouterAttributionTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the router replaces application-set workflow headers, in any letter case, with the command's actor")
    void routerStampsTheCommandsActorAndStripsApplicationSetWorkflowHeaders() {
        // Given a recorder that keeps the intent it is handed and an intent whose application-set headers try to name
        // another actor, in different letter cases, plus an unrelated header
        AtomicReference<WorkflowSideEffectIntent> recorded = new AtomicReference<>();
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.SERVICE);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            recorded.set(invocation.getArgument(0));
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        Map<String, String> applicationHeaders = new HashMap<>();
        applicationHeaders.put("x-workflow-actor-id", "mallory");
        applicationHeaders.put("X-Workflow-Actor-Reason", "eve");
        applicationHeaders.put("X-WORKFLOW-Attribution", "forged");
        applicationHeaders.put("x-workflow-custom", "x");
        applicationHeaders.put("x-trace", "kept");
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.SERVICE,
                "billing.charge",
                new Object(),
                applicationHeaders,
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SegmentCommand command = new SegmentCommand(
                7L,
                id,
                "START",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new WorkflowActor.Service("billing\n%"));

        // When the router routes the intent for the command
        router.route(intent, command, mock(SqlClient.class));

        // Then the recorder got the command's actor under the kind and id names and no application-set workflow
        // header, while an unrelated header is kept
        Map<String, String> headers = recorded.get().headers();
        assertThat(headers)
                .containsEntry("x-workflow-actor-kind", "SERVICE")
                .containsEntry("x-workflow-actor-id", "billing%0A%25")
                .containsEntry("x-trace", "kept")
                .doesNotContainKey("x-workflow-attribution")
                .doesNotContainKey("x-workflow-actor-reason")
                .doesNotContainKey("x-workflow-on-behalf-of");
        assertThat(headers.keySet())
                .extracting(name -> name.toLowerCase(Locale.ROOT))
                .doesNotContain("x-workflow-custom")
                .doesNotHaveDuplicates();
        assertThat(headers.values()).doesNotContain("mallory", "eve", "x", "forged");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a system command stamps its reason and no id")
    void routerOmitsAbsentAttributionValues() {
        // Given a system command and an intent carrying forged actor headers
        AtomicReference<WorkflowSideEffectIntent> recorded = new AtomicReference<>();
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.SERVICE);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            recorded.set(invocation.getArgument(0));
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.SERVICE,
                "billing.charge",
                new Object(),
                Map.of("X-Workflow-Actor-Id", "eve", "x-workflow-actor-reason", "mallory"),
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SegmentCommand command = new SegmentCommand(
                8L, id, "TIMER", null, null, null, null, null, null, null, null, new WorkflowActor.System("timer"));

        // When the router routes the intent for the command
        router.route(intent, command, mock(SqlClient.class));

        // Then the system is named with its reason, and no id appears
        assertThat(recorded.get().headers())
                .containsEntry("x-workflow-actor-kind", "SYSTEM")
                .containsEntry("x-workflow-actor-reason", "timer")
                .doesNotContainKey("x-workflow-attribution")
                .doesNotContainKey("x-workflow-actor-id")
                .doesNotContainKey("X-Workflow-Actor-Id");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a workflow event carries only the actor kind, never an id")
    void workflowEventCarriesNoSubjectIdentifier() {
        // Given a recorder of workflow events and a command by a user
        AtomicReference<WorkflowSideEffectIntent> recorded = new AtomicReference<>();
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.WORKFLOW_EVENT);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            recorded.set(invocation.getArgument(0));
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.WORKFLOW_EVENT,
                "event",
                new Object(),
                Map.of("x-workflow-actor-id", "mallory"),
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SegmentCommand command = new SegmentCommand(
                9L, id, "SIGNAL", null, null, null, null, null, null, null, null, new WorkflowActor.User("alice"));

        // When the router routes the event for the command
        router.route(intent, command, mock(SqlClient.class));

        // Then only the kind is stamped
        assertThat(recorded.get().headers())
                .containsEntry("x-workflow-actor-kind", "USER")
                .doesNotContainKey("x-workflow-attribution")
                .doesNotContainKey("x-workflow-actor-id")
                .doesNotContainKey("x-workflow-actor-reason");
        assertThat(recorded.get().headers().values()).doesNotContain("alice", "why", "mallory");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a user is named by id, and an anonymous caller and a command with no actor by kind alone")
    void routerNamesAUserAndStampsOnlyTheKindForAnonymousAndNone() {
        // Given a recorder of service dispatches
        List<Map<String, String>> routed = new ArrayList<>();
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.SERVICE);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            WorkflowSideEffectIntent seen = invocation.getArgument(0);
            routed.add(seen.headers());
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.SERVICE,
                "billing.charge",
                new Object(),
                Map.of(),
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SqlClient tx = mock(SqlClient.class);

        // When intents are routed for a command by a user, by an anonymous caller and by no actor
        router.route(intent, commandBy(1L, id, "a b"), tx);
        router.route(
                intent,
                new SegmentCommand(
                        2L,
                        id,
                        "SIGNAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new WorkflowActor.Anonymous()),
                tx);
        router.route(
                intent, new SegmentCommand(3L, id, "SIGNAL", null, null, null, null, null, null, null, null, null), tx);

        // Then the user is named by its encoded id, and the other two by their kind alone
        assertThat(routed.get(0))
                .containsEntry("x-workflow-actor-kind", "USER")
                .containsEntry("x-workflow-actor-id", "a%20b")
                .doesNotContainKey("x-workflow-actor-reason");
        assertThat(routed.get(1))
                .containsEntry("x-workflow-actor-kind", "ANONYMOUS")
                .doesNotContainKey("x-workflow-actor-id")
                .doesNotContainKey("x-workflow-actor-reason");
        assertThat(routed.get(2))
                .containsEntry("x-workflow-actor-kind", "NONE")
                .doesNotContainKey("x-workflow-actor-id")
                .doesNotContainKey("x-workflow-actor-reason");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName(
            "a command executed inside another's segment attributes its intents to itself, then the outer one resumes")
    void nestedCommandAttributesItsOwnIntentsAndRestoresTheOuterOne() {
        // Given a segment of user A that applies a signal recorded by user B
        List<String> actors = new ArrayList<>();
        WorkflowSideEffectRecorder<SqlClient> recorder = mock(WorkflowSideEffectRecorder.class);
        when(recorder.kind()).thenReturn(IntentKind.SERVICE);
        when(recorder.record(any(), any())).thenAnswer(invocation -> {
            WorkflowSideEffectIntent routed = invocation.getArgument(0);
            actors.add(routed.headers().get("x-workflow-actor-id"));
            return Future.succeededFuture(RecorderResult.empty());
        });
        RecorderRouter router = new RecorderRouter(Set.of(recorder), Set.of());
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.SERVICE,
                "billing.charge",
                new Object(),
                Map.of(),
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SegmentCommand segment = commandBy(1L, id, "A");
        SegmentCommand signal = commandBy(2L, id, "B");
        SqlClient tx = mock(SqlClient.class);

        // When the segment routes an intent, applies the signal, which routes one, and routes another
        router.executing(segment, tx, () -> {
            router.route(intent, tx);
            return router.executing(signal, tx, () -> router.route(intent, tx))
                    .compose(done -> router.route(intent, tx));
        });

        // Then the signal's intent is B's, and the segment's before and after it are A's
        assertThat(actors).containsExactly("A", "B", "A");
    }

    private static SegmentCommand commandBy(long commandId, WorkflowInstanceId id, String user) {
        return new SegmentCommand(
                commandId, id, "SIGNAL", null, null, null, null, null, null, null, null, new WorkflowActor.User(user));
    }
}
