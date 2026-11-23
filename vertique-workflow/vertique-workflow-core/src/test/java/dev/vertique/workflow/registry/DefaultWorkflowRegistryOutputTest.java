// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that registration checks the output mapper callback a plan names. */
class DefaultWorkflowRegistryOutputTest {

    record State(String id) {}

    record Start(String id) {}

    private static WorkflowPlan planNamingAnOutput() {
        return new WorkflowPlan(
                "order-output",
                1L,
                "hash",
                State.class.getName(),
                "done",
                List.of(new CompleteNode("done")),
                null,
                "order.Receipt",
                new CallbackId("$output.mapper"));
    }

    @Test
    void aPlanThatNamesAnOutputMapperTheCallbackRegistryLacksIsRefused() {
        WorkflowBuilder<State> noOutput = new WorkflowBuilder<>();
        noOutput.init(Start.class, s -> new State(s.id())).initialStep("done").complete("done");

        assertThatThrownBy(() -> DefaultWorkflowRegistry.validateCallbackConsistency(
                        planNamingAnOutput(), noOutput.callbackRegistry(), "default", "order-output", 1L))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("outputMapper")
                .hasMessageContaining("$output.mapper");
    }

    @Test
    void aPlanWhoseOutputMapperIsRegisteredIsAccepted() {
        WorkflowBuilder<State> withOutput = new WorkflowBuilder<>();
        withOutput
                .init(Start.class, s -> new State(s.id()))
                .output(String.class, State::id)
                .initialStep("done")
                .complete("done");

        assertThatCode(() -> DefaultWorkflowRegistry.validateCallbackConsistency(
                        planNamingAnOutput(), withOutput.callbackRegistry(), "default", "order-output", 1L))
                .doesNotThrowAnyException();
        assertThat(withOutput.outputType()).isEqualTo(String.class);
    }
}
