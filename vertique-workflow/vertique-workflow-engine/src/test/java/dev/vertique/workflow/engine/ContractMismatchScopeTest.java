// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that a contract refusal carries the scope of the definition the instance is pinned to. */
class ContractMismatchScopeTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final String DEFINITION_ID = "scoped-refusal-def";
    private static final String PLAN_HASH = "plan-hash";
    private static final String TENANT = "tenant-a";

    private static RuntimeWorkflow pinnedWorkflow() {
        WaitSignalNode wait = new WaitSignalNode(
                "wait-signal", "approve", Object.class.getName(), new CallbackId("state-updater"), "complete", null);
        List<WorkflowNode> nodes = List.of(wait, new CompleteNode("complete"));
        WorkflowPlan plan =
                new WorkflowPlan(DEFINITION_ID, 3L, PLAN_HASH, Object.class.getName(), wait.stepId(), nodes, null);
        return RuntimeWorkflow.of(
                plan,
                "approval",
                Object.class,
                Object.class,
                payload -> Map.of(),
                Map.of(),
                mock(WorkflowCallbackRegistry.class));
    }

    private static WorkflowInstance instancePinnedIn(String startScope, String definitionScope) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                3L,
                PLAN_HASH,
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "wait-signal",
                WaitType.SIGNAL,
                "approve",
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                DurableMetadata.empty(),
                startScope,
                definitionScope);
    }

    @Test
    @DisplayName("the refusal names the scope of the definition the instance is pinned to, not the start scope")
    void refusalCarriesThePinnedDefinitionScope() {
        // Given: an instance started in the default scope and pinned to a definition of tenant-a
        WorkflowInstance instance = instancePinnedIn("default", TENANT);

        // When: a client of another contract is checked against the pinned definition
        WorkflowDefinitionContractMismatchException refusal =
                ContractMismatch.refusal(instance, pinnedWorkflow(), "other", "signal");

        // Then: the typed key carries the pinned definition's scope, id and version
        assertThat(refusal).isNotNull();
        assertThat(refusal.definitionKey().scopeKey()).isEqualTo(TENANT);
        assertThat(refusal.definitionKey().definitionId()).isEqualTo(DEFINITION_ID);
        assertThat(refusal.definitionKey().definitionVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("an instance pinned to the default scope is refused with the default scope")
    void refusalCarriesTheDefaultScopeForADefaultPin() {
        // Given: an instance started in tenant-a and pinned to a definition of the default scope
        WorkflowInstance instance = instancePinnedIn(TENANT, "default");

        // When: a client of another contract is checked
        WorkflowDefinitionContractMismatchException refusal =
                ContractMismatch.refusal(instance, pinnedWorkflow(), "other", "query");

        // Then: the typed key carries the default scope
        assertThat(refusal).isNotNull();
        assertThat(refusal.definitionKey().scopeKey()).isEqualTo("default");
    }
}
