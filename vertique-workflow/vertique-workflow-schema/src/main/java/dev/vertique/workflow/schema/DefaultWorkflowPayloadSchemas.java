// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.PathForm;
import dev.vertique.json.schema.validation.ViolationDetails;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import jakarta.annotation.Nullable;
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
 *
 * <p>Validation resolves the same keys, then runs the compiled validator held by the cache entry inside a window of
 * its pattern-input guard. Nothing is generated, compiled or loaded per call, and the submitted payload is only read.
 * At most 100 violations are returned, followed by one truncation detail when more were produced. A definition that
 * declares no start type has nothing to validate, so every start payload is accepted.
 */
@Singleton
final class DefaultWorkflowPayloadSchemas implements WorkflowPayloadSchemas {

    /** The largest number of violations returned before the truncation detail. */
    private static final int MAX_VIOLATIONS = 100;

    private static final String TRUNCATED_MESSAGE = "further violations omitted";
    private static final String TRUNCATED_TYPE = "truncated";

    private static final String VOID_PRIMITIVE = "void";
    private static final String VOID_WRAPPER = Void.class.getName();

    private static final String UNKNOWN_DEFINITION = "definition is not registered";
    private static final String UNKNOWN_DECISION = "decision is not declared for this task step";
    private static final String PAYLOAD_REQUIRED = "payload is required";
    private static final String VOID_DECISION_PAYLOAD = "payload is not accepted for a decision without a payload type";
    private static final String VOID_START_PAYLOAD = "payload is not accepted for a start type without a payload";
    private static final String DEFINITION_TYPE = "definition";
    private static final String DECISION_TYPE = "decision";
    private static final String PAYLOAD_TYPE = "payload";

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
        return resolve(definitionId, definitionVersion)
                .flatMap(workflow -> findDecision(workflow, stepId, decisionName))
                .map(this::descriptorOf);
    }

    @Override
    public PayloadValidationResult validateDecision(
            String definitionId,
            long definitionVersion,
            String stepId,
            String decisionName,
            @Nullable JsonObject payload) {
        Optional<RuntimeWorkflow> workflow = resolve(definitionId, definitionVersion);
        if (workflow.isEmpty()) {
            return single(UNKNOWN_DEFINITION, DEFINITION_TYPE);
        }
        Optional<HumanTaskNode.TaskDecision> decision = findDecision(workflow.get(), stepId, decisionName);
        if (decision.isEmpty()) {
            return single(UNKNOWN_DECISION, DECISION_TYPE);
        }
        return validatePayload(decision.get().payloadTypeName(), payload, VOID_DECISION_PAYLOAD);
    }

    @Override
    public PayloadValidationResult validateStart(
            String definitionId, long definitionVersion, @Nullable JsonObject payload) {
        Optional<RuntimeWorkflow> workflow = resolve(definitionId, definitionVersion);
        if (workflow.isEmpty()) {
            return single(UNKNOWN_DEFINITION, DEFINITION_TYPE);
        }
        Class<?> startType = workflow.get().startPayloadType();
        if (startType == null) {
            return new PayloadValidationResult(List.of());
        }
        return validatePayload(startType.getName(), payload, VOID_START_PAYLOAD);
    }

    private static Optional<HumanTaskNode.TaskDecision> findDecision(
            RuntimeWorkflow workflow, String stepId, String decisionName) {
        for (WorkflowNode node : workflow.plan().nodes()) {
            if (node instanceof HumanTaskNode task && task.stepId().equals(stepId)) {
                for (HumanTaskNode.TaskDecision decision : task.decisions()) {
                    if (decision.name().equals(decisionName)) {
                        return Optional.of(decision);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Validates a payload against the cached schema of a payload type. A type without a payload accepts only
     * {@code null}, matching the engine. The cache entry is read first so a missing registration validator is loud
     * for a {@code null} payload too.
     */
    private PayloadValidationResult validatePayload(
            String typeName, @Nullable JsonObject payload, String voidPayloadMessage) {
        if (isVoid(typeName)) {
            return payload == null ? new PayloadValidationResult(List.of()) : single(voidPayloadMessage, PAYLOAD_TYPE);
        }
        CachedPayloadSchema entry = entryOf(typeName);
        if (payload == null) {
            return single(PAYLOAD_REQUIRED, PAYLOAD_TYPE);
        }
        OutputUnit result;
        try (var window = entry.guard().openWindow()) {
            result = entry.validator().validate(payload);
        } catch (RuntimeException thrown) {
            return ViolationDetails.patternInputBound(thrown)
                    .map(detail -> new PayloadValidationResult(List.of(detail)))
                    .orElseThrow(() -> thrown);
        }
        return new PayloadValidationResult(
                capped(ViolationDetails.collect(result, entry.document(), false, PathForm.RFC6901)));
    }

    /**
     * Keeps the first 100 violations in the order produced and appends one truncation detail when more were
     * produced, so a result never has more than 101 entries.
     */
    private static List<ViolationDetail> capped(List<ViolationDetail> violations) {
        if (violations.size() <= MAX_VIOLATIONS) {
            return violations;
        }
        List<ViolationDetail> retained = new ArrayList<>(MAX_VIOLATIONS + 1);
        retained.addAll(violations.subList(0, MAX_VIOLATIONS));
        retained.add(ViolationDetail.unlocated(TRUNCATED_MESSAGE, TRUNCATED_TYPE));
        return retained;
    }

    private static PayloadValidationResult single(String message, String type) {
        return new PayloadValidationResult(List.of(ViolationDetail.unlocated(message, type)));
    }

    private static boolean isVoid(String typeName) {
        return VOID_PRIMITIVE.equals(typeName) || VOID_WRAPPER.equals(typeName);
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
        if (isVoid(typeName)) {
            return null;
        }
        CachedPayloadSchema entry = entryOf(typeName);
        return new PayloadSchema(entry.document(), entry.propertyOrder());
    }

    private CachedPayloadSchema entryOf(String typeName) {
        return cache.findByTypeName(typeName)
                .orElseThrow(() -> new IllegalStateException(
                        "the schema module's registration validator did not run for this registry: install"
                                + " WorkflowSchemaModule in the component that provides WorkflowRegistry"
                                + " (payload type '" + typeName + "')"));
    }
}
