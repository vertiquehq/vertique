// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the attribution headers the router stamps on a segment's intent from the command the segment executes: the
 * command's actor, the party it acted for and its attribution status, with every application-set header whose name
 * starts with the workflow prefix removed first, in any letter case.
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
        applicationHeaders.put("X-Workflow-On-Behalf-Of", "eve");
        applicationHeaders.put("X-WORKFLOW-Attribution", "VERIFIED");
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
                new CommandActor(CommandActor.Kind.SERVICE, "billing", null, "u-7", CommandActor.Attribution.DEGRADED));

        // When the router routes the intent for the command
        router.route(intent, command, mock(SqlClient.class));

        // Then the recorder got the command's actor under the five attribution names and no application-set workflow
        // header, while an unrelated header is kept
        Map<String, String> headers = recorded.get().headers();
        assertThat(headers)
                .containsEntry("x-workflow-actor-kind", "SERVICE")
                .containsEntry("x-workflow-actor-id", "billing")
                .containsEntry("x-workflow-on-behalf-of", "u-7")
                .containsEntry("x-workflow-attribution", "DEGRADED")
                .containsEntry("x-trace", "kept")
                .doesNotContainKey("x-workflow-actor-reason");
        assertThat(headers.keySet())
                .extracting(name -> name.toLowerCase(Locale.ROOT))
                .doesNotContain("x-workflow-custom")
                .doesNotHaveDuplicates();
        assertThat(headers.values()).doesNotContain("mallory", "eve", "x");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a command with no party acted for and no id stamps neither header")
    void routerOmitsAbsentAttributionValues() {
        // Given a system command and an intent carrying a forged party-acted-for header
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
                Map.of("X-Workflow-On-Behalf-Of", "eve", "x-workflow-actor-id", "mallory"),
                WorkflowSideEffectIntent.Correlation.singlePath(id, 1L, "def", "acme", "tenant-1", "charge"));
        SegmentCommand command = new SegmentCommand(
                8L, id, "TIMER", null, null, null, null, null, null, null, null, CommandActor.system("timer"));

        // When the router routes the intent for the command
        router.route(intent, command, mock(SqlClient.class));

        // Then the system is named with its reason, and neither an id nor a party acted for appears
        assertThat(recorded.get().headers())
                .containsEntry("x-workflow-actor-kind", "SYSTEM")
                .containsEntry("x-workflow-actor-reason", "timer")
                .containsEntry("x-workflow-attribution", "VERIFIED")
                .doesNotContainKey("x-workflow-actor-id")
                .doesNotContainKey("x-workflow-on-behalf-of")
                .doesNotContainKey("X-Workflow-On-Behalf-Of");
    }
}
