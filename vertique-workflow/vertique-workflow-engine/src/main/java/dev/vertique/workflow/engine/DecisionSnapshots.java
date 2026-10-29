// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the decision snapshot a new task stores: the descriptors of the decisions its node offers, each with the JSON
 * Schema of its payload when a producer supplies one.
 */
final class DecisionSnapshots {

    private static final Logger log = LoggerFactory.getLogger(DecisionSnapshots.class);

    private DecisionSnapshots() {
        // static helpers only
    }

    /**
     * Describes the decisions of a human task node.
     *
     * <p>A decision without a payload, and every decision when no producer is installed or the producer cannot describe
     * the plan, carries no schema. A producer failure never fails task creation: the executor validates every
     * completion against the plan whatever the snapshot holds.
     *
     * @param node     the human task node
     * @param workflow the runtime workflow the node belongs to
     * @param producer the schema producer, if one is installed
     * @return the descriptors, in the node's decision order
     */
    static List<TaskDecisionDescriptor> of(
            HumanTaskNode node, RuntimeWorkflow workflow, Optional<WorkflowPlanSchemaProducer> producer) {
        Map<String, JsonObject> schemas = Map.of();
        if (producer.isPresent()) {
            try {
                schemas = producer.get().decisionSchemas(workflow).getOrDefault(node.stepId(), Map.of());
            } catch (RuntimeException failure) {
                log.warn(
                        "The decision schemas of step '{}' could not be produced; the task is created without them ({})",
                        node.stepId(),
                        failure.getClass().getName());
            }
        }
        Map<String, JsonObject> bySchema = schemas;
        return node.decisions().stream()
                .map(decision -> new TaskDecisionDescriptor(
                        decision.name(),
                        decision.payloadTypeName(),
                        decision.nextStepId(),
                        bySchema.get(decision.name())))
                .toList();
    }
}
