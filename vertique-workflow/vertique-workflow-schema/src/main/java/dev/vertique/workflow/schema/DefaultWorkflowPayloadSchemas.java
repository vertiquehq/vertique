// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.PathForm;
import dev.vertique.json.schema.validation.ViolationDetails;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles descriptors from the workflow registry and the schemas cached at registration.
 *
 * <p>The service is stateless and holds no plan after a call. It loads the pinned definition through
 * {@link WorkflowRegistry#load(DefinitionKey)}, so a version that is not resident is built on demand, then walks the human tasks in plan
 * order and their decisions in declaration order, and looks each payload type up in the cache by its logical name.
 * It never loads a class and never generates a schema. A definition that is registered but has no cached schema for
 * one of its payload types means the registration validator was not installed for the registry; that is reported as
 * an {@link IllegalStateException} that fails the returned future rather than being thrown from the call, and not
 * as an unknown definition. A version that cannot run on this node, because no row supplies it or its stored source
 * does not build, is an unknown definition and its cause is logged, not returned; any other load failure fails the
 * future with the load's own failure. The service does not decide who may ask: a caller authorizes access to the
 * definition and checks its scope before calling.
 *
 * <p>Validation resolves the same keys, then runs the compiled validator held by the cache entry inside a window of
 * its pattern-input guard. Nothing is generated, compiled or loaded per call, and the submitted payload is only read.
 * Every violation is returned in the order produced. A definition that
 * declares no start type has nothing to validate, so every start payload is accepted.
 *
 * <p>Every call resolves a plan, so a recording-only node ({@code workflow.executor.enabled=false}), which builds none,
 * fails each call with {@link WorkflowNodeRoleException}.
 */
@Singleton
final class DefaultWorkflowPayloadSchemas implements WorkflowPayloadSchemas {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowPayloadSchemas.class);

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
    private final WorkflowTypeNames typeNames;
    private final boolean executorNode;

    DefaultWorkflowPayloadSchemas(WorkflowRegistry registry, PayloadSchemaCache cache) {
        this(registry, cache, new DefaultWorkflowTypeNames(), Optional.empty());
    }

    DefaultWorkflowPayloadSchemas(WorkflowRegistry registry, PayloadSchemaCache cache, WorkflowTypeNames typeNames) {
        this(registry, cache, typeNames, Optional.empty());
    }

    @Inject
    DefaultWorkflowPayloadSchemas(
            WorkflowRegistry registry,
            PayloadSchemaCache cache,
            WorkflowTypeNames typeNames,
            Optional<WorkflowExecutorConfig> executorConfig) {
        this.registry = registry;
        this.cache = cache;
        this.typeNames = typeNames;
        this.executorNode = executorConfig.map(WorkflowExecutorConfig::enabled).orElse(true);
    }

    @Override
    public Future<Optional<WorkflowDefinitionDescriptor>> describe(DefinitionKey key) {
        if (!executorNode) return Future.failedFuture(new WorkflowNodeRoleException("describe"));
        return resolve(key)
                .map(found -> found.map(workflow -> {
                    Class<?> startType = workflow.startPayloadType();
                    PayloadSchema start = startType == null ? null : schemaOf(typeNames.nameOf(startType));
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
                    return new WorkflowDefinitionDescriptor(key.definitionId(), key.definitionVersion(), start, steps);
                }));
    }

    @Override
    public Future<Optional<DecisionDescriptor>> decision(DefinitionKey key, String stepId, String decisionName) {
        if (!executorNode) return Future.failedFuture(new WorkflowNodeRoleException("decision"));
        return resolve(key).map(found -> found.flatMap(workflow -> findDecision(workflow, stepId, decisionName))
                .map(this::descriptorOf));
    }

    @Override
    public Future<PayloadValidationResult> validateDecision(
            DefinitionKey key, String stepId, String decisionName, @Nullable JsonObject payload) {
        if (!executorNode) return Future.failedFuture(new WorkflowNodeRoleException("validateDecision"));
        return resolve(key).map(workflow -> {
            if (workflow.isEmpty()) {
                return single(UNKNOWN_DEFINITION, DEFINITION_TYPE);
            }
            Optional<HumanTaskNode.TaskDecision> decision = findDecision(workflow.get(), stepId, decisionName);
            if (decision.isEmpty()) {
                return single(UNKNOWN_DECISION, DECISION_TYPE);
            }
            return validatePayload(decision.get().payloadTypeName(), payload, VOID_DECISION_PAYLOAD);
        });
    }

    @Override
    public Future<PayloadValidationResult> validateStart(DefinitionKey key, @Nullable JsonObject payload) {
        if (!executorNode) return Future.failedFuture(new WorkflowNodeRoleException("validateStart"));
        return resolve(key).map(workflow -> {
            if (workflow.isEmpty()) {
                return single(UNKNOWN_DEFINITION, DEFINITION_TYPE);
            }
            Class<?> startType = workflow.get().startPayloadType();
            if (startType == null) {
                return new PayloadValidationResult(List.of());
            }
            return validatePayload(typeNames.nameOf(startType), payload, VOID_START_PAYLOAD);
        });
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
        return new PayloadValidationResult(ViolationDetails.collect(result, entry.document(), false, PathForm.RFC6901));
    }

    private static PayloadValidationResult single(String message, String type) {
        return new PayloadValidationResult(List.of(ViolationDetail.unlocated(message, type)));
    }

    private static boolean isVoid(String typeName) {
        return VOID_PRIMITIVE.equals(typeName) || VOID_WRAPPER.equals(typeName);
    }

    /**
     * Loads the plan. A version that cannot run on this node (no row supplies it, or the row does not build) is
     * answered as an unknown definition, whatever the reason, so the answer does not tell a caller what the stored
     * source of a definition holds; the cause is logged for the operator. Any other failure fails the future.
     */
    private Future<Optional<RuntimeWorkflow>> resolve(DefinitionKey key) {
        return registry.load(key).map(Optional::of).recover(failure -> {
            if (!(failure instanceof WorkflowDefinitionException
                    || failure instanceof WorkflowVersionPinUnavailableException)) {
                return Future.failedFuture(failure);
            }
            if (!(failure instanceof WorkflowDefinitionNotFoundException)) {
                log.warn(
                        "Definition scope '{}' id '{}' version {} is reported as unknown because its plan does not"
                                + " load: {}",
                        EchoedNames.bounded(key.scopeKey()),
                        EchoedNames.bounded(key.definitionId()),
                        key.definitionVersion(),
                        failure.getClass().getName());
                log.debug("Plan load failure", failure);
            }
            return Future.succeededFuture(Optional.empty());
        });
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
