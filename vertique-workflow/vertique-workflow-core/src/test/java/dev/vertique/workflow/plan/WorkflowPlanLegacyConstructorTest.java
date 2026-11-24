// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.registry.CallbackId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the seven-argument {@link WorkflowPlan} constructor kept from before a definition could declare an output:
 * a plan built with it declares none.
 */
class WorkflowPlanLegacyConstructorTest {

    @Test
    @DisplayName("a plan built with the seven-argument constructor declares no output and keeps its other components")
    void sevenArgumentConstructorDeclaresNoOutput() {
        CompleteNode done = new CompleteNode("done");
        CallbackId subject = new CallbackId("subject");

        WorkflowPlan plan = new WorkflowPlan("legacy", 3L, "hash", "java.lang.String", "done", List.of(done), subject);

        assertThat(plan.outputTypeName()).isNull();
        assertThat(plan.outputMapperCallbackId()).isNull();
        assertThat(plan.definitionId()).isEqualTo("legacy");
        assertThat(plan.definitionVersion()).isEqualTo(3L);
        assertThat(plan.planHash()).isEqualTo("hash");
        assertThat(plan.stateTypeName()).isEqualTo("java.lang.String");
        assertThat(plan.initialStepId()).isEqualTo("done");
        assertThat(plan.nodes()).containsExactly(done);
        assertThat(plan.subjectResolverCallbackId()).isEqualTo(subject);
    }

    @Test
    @DisplayName(
            "the seven-argument constructor equals the canonical constructor given null for both output components")
    void sevenArgumentConstructorEqualsTheCanonicalFormWithNullOutput() {
        List<WorkflowNode> nodes = List.of(new CompleteNode("done"));

        WorkflowPlan legacy = new WorkflowPlan("legacy", 1L, "hash", "java.lang.String", "done", nodes, null);
        WorkflowPlan canonical =
                new WorkflowPlan("legacy", 1L, "hash", "java.lang.String", "done", nodes, null, null, null);

        assertThat(legacy).isEqualTo(canonical);
    }
}
