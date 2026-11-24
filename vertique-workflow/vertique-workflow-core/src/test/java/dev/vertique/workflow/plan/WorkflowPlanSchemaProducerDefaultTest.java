// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the default of {@link WorkflowPlanSchemaProducer#outputSchema}: an implementation written before the declared
 * output existed, which does not override it, describes no output.
 */
class WorkflowPlanSchemaProducerDefaultTest {

    /** The two methods every implementation had before the declared output; {@code outputSchema} is not overridden. */
    private static final class PreOutputProducer implements WorkflowPlanSchemaProducer {
        @Override
        public JsonObject startSchema(RuntimeWorkflow plan) {
            return new JsonObject().put("type", "null");
        }

        @Override
        public Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("outputSchema of a producer that does not override it returns null")
    void outputSchemaDefaultsToNull() {
        WorkflowPlan plan = new WorkflowPlan(
                "legacy", 1L, "hash", "java.lang.String", "done", List.of(new CompleteNode("done")), null);
        RuntimeWorkflow workflow =
                RuntimeWorkflow.of(plan, "contract", String.class, String.class, x -> x, Map.of(), null);

        assertThat(new PreOutputProducer().outputSchema(workflow)).isNull();
    }
}
