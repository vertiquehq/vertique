// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles descriptors from the workflow registry and the schemas cached at registration.
 *
 * <p>The service is stateless. It resolves the pinned definition from the registry, walks the human tasks in plan
 * order and their decisions in declaration order, and looks each payload type up in the cache by name. It never loads
 * a class and never generates a schema. A definition that is registered but has no cached schema for one of its
 * payload types means the registration validator was not installed for the registry; that is reported as an
 * {@link IllegalStateException} rather than as an unknown definition.
 */
@Singleton
final class DefaultWorkflowPayloadSchemas implements WorkflowPayloadSchemas {

    private static final String VOID_PRIMITIVE = "void";
    private static final String VOID_WRAPPER = Void.class.getName();

    private final WorkflowRegistry registry;
    private final PayloadSchemaCache cache;

    @Inject
    DefaultWorkflowPayloadSchemas(WorkflowRegistry registry, PayloadSchemaCache cache) {
        this.registry = registry;
        this.cache = cache;
    }

    @Override
    public Optional<WorkflowDefinitionDescriptor> describe(String definitionId, long definitionVersion) {
        return resolve(definitionId, definitionVersion).map(workflow -> {
            Class<?> startType = workflow.startPayloadType();
            PayloadSchema start = startType == null ? null : schemaOf(startType.getName());
            List<HumanTaskStepDescriptor> steps = new ArrayList<>();
            for (WorkflowNode node : workflow.plan().nodes()) {
                if (node instanceof HumanTaskNode task) {
                    List<DecisionDescriptor> decisions = new ArrayList<>();
                    for (HumanTaskNode.TaskDecision decision : task.decisions()) {
                        decisions.add(descriptorOf(decision));
                    }
                    steps.add(new HumanTaskStepDescriptor(task.stepId(), decisions));
                }
            }
            return new WorkflowDefinitionDescriptor(definitionId, definitionVersion, start, steps);
        });
    }

    @Override
    public Optional<DecisionDescriptor> decision(
            String definitionId, long definitionVersion, String stepId, String decisionName) {
        return resolve(definitionId, definitionVersion).flatMap(workflow -> {
            for (WorkflowNode node : workflow.plan().nodes()) {
                if (node instanceof HumanTaskNode task && task.stepId().equals(stepId)) {
                    for (HumanTaskNode.TaskDecision decision : task.decisions()) {
                        if (decision.name().equals(decisionName)) {
                            return Optional.of(descriptorOf(decision));
                        }
                    }
                }
            }
            return Optional.empty();
        });
    }

    private Optional<RuntimeWorkflow> resolve(String definitionId, long definitionVersion) {
        try {
            return Optional.of(registry.resolvePinned(definitionId, definitionVersion));
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException e) {
            return Optional.empty();
        }
    }

    private DecisionDescriptor descriptorOf(HumanTaskNode.TaskDecision decision) {
        return new DecisionDescriptor(decision.name(), decision.nextStepId(), schemaOf(decision.payloadTypeName()));
    }

    private PayloadSchema schemaOf(String typeName) {
        if (VOID_PRIMITIVE.equals(typeName) || VOID_WRAPPER.equals(typeName)) {
            return null;
        }
        CachedPayloadSchema entry = cache.findByTypeName(typeName)
                .orElseThrow(() -> new IllegalStateException(
                        "the schema module's registration validator did not run for this registry: install"
                                + " WorkflowSchemaModule in the component that provides WorkflowRegistry"
                                + " (payload type '" + typeName + "')"));
        return new PayloadSchema(entry.document(), entry.propertyOrder());
    }
}
