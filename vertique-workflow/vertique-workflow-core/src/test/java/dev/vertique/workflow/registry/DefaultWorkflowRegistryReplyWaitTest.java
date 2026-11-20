// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.json.VertiqueJson;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.ServiceFailure;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies registry checks and payload types for reply waits. */
class DefaultWorkflowRegistryReplyWaitTest {
    interface EmptyContract {}

    @Test
    @DisplayName("registry checks both names and the failure route")
    void registryChecksCountTheFailureRouteAndBothNames() throws Exception {
        var good = plan("done", null, false);
        var callbacks = mock(WorkflowCallbackRegistry.class);
        assertThatCode(() -> DefaultWorkflowRegistry.validatePlanGraph(good, "scope", "test", 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> DefaultWorkflowRegistry.validateCallbackConsistency(good, callbacks, "scope", "test", 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> DefaultWorkflowRegistry.validateSignalUniqueness(good, "scope", "test", 1))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        DefaultWorkflowRegistry.validateDeclaredSignals(good, EmptyContract.class, "scope", "test", 1))
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                        DefaultWorkflowRegistry.validatePlanGraph(plan("missing", null, false), "scope", "test", 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("wait");
        CallbackId missing = new CallbackId("missing-callback");
        when(callbacks.stateUpdater(missing)).thenThrow(new IllegalArgumentException());
        assertThatThrownBy(() -> DefaultWorkflowRegistry.validateCallbackConsistency(
                        plan("done", missing, false), callbacks, "scope", "test", 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("wait");
        assertThatThrownBy(() ->
                        DefaultWorkflowRegistry.validateSignalUniqueness(plan("done", null, true), "scope", "test", 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("vertique.reply.failed.call");
        assertThat(DefaultWorkflowRegistry.signalPayloadTypesWithReplies(good, Map.of()))
                .containsEntry("vertique.reply.failed.call", ServiceFailure.class);
        var json = VertiqueJson.mapper()
                .readTree(VertiqueJson.mapper()
                        .writeValueAsString(new ServiceFailure(ServiceFailure.Reason.PERMANENT, 3)));
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("reason").asText()).isEqualTo("PERMANENT");
        assertThat(json.get("attempts").asInt()).isEqualTo(3);
    }

    private static WorkflowPlan plan(String failureNext, CallbackId failureUpdater, boolean duplicate) {
        var wait = new WaitSignalNode(
                "wait",
                "vertique.reply.ok.call",
                "java.lang.String",
                new CallbackId("update"),
                "done",
                null,
                null,
                new WaitSignalNode.ReplyWait("call", "vertique.reply.failed.call", failureUpdater, failureNext, false));
        var nodes = new java.util.ArrayList<dev.vertique.workflow.plan.WorkflowNode>(List.of(
                new ServiceDispatchNode("call", "target", new CallbackId("payload"), null, "wait"),
                wait,
                new CompleteNode("done")));
        if (duplicate)
            nodes.add(new WaitSignalNode(
                    "second",
                    "vertique.reply.failed.call",
                    "java.lang.String",
                    new CallbackId("update"),
                    "done",
                    null));
        return new WorkflowPlan("test", 1, "", "java.lang.String", "call", nodes, null);
    }
}
