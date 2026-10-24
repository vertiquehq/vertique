// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the start and decision payload schemas of a plan from the schemas cached at registration.
 *
 * <p>A payload type that the registration validator has cached is read from the cache, so the result equals what
 * payload validation uses. A type that is not cached yet, as for a candidate that is not registered, is described
 * by the same generator under the same profile; nothing is cached or compiled for it. The producer is stateless
 * apart from the shared cache and never changes the plan.
 */
@Singleton
final class DefaultWorkflowPlanSchemaProducer implements WorkflowPlanSchemaProducer {

    private static final String VOID_PRIMITIVE = "void";
    private static final String VOID_WRAPPER = Void.class.getName();

    private final PayloadSchemaCache cache;
    private final PayloadSchemaGenerator generator;
    private final WorkflowTypeNames typeNames;

    DefaultWorkflowPlanSchemaProducer(PayloadSchemaCache cache) {
        this(cache, null, new DefaultWorkflowTypeNames());
    }

    @Inject
    DefaultWorkflowPlanSchemaProducer(
            JsonMapperProfileRegistry profileRegistry,
            JsonConfig jsonConfig,
            Optional<Validator> validator,
            PayloadSchemaCache cache,
            WorkflowTypeNames typeNames) {
        this(cache, new PayloadSchemaGenerator(profileRegistry, jsonConfig, validator), typeNames);
    }

    private DefaultWorkflowPlanSchemaProducer(
            PayloadSchemaCache cache, PayloadSchemaGenerator generator, WorkflowTypeNames typeNames) {
        this.cache = cache;
        this.generator = generator;
        this.typeNames = typeNames;
    }

    @Override
    public JsonObject startSchema(RuntimeWorkflow plan) {
        Class<?> startType = plan.startPayloadType();
        if (startType == null || startType == void.class || startType == Void.class) {
            return null;
        }
        return schemaOf(typeNames.nameOf(startType), startType);
    }

    @Override
    public Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan) {
        Map<String, Map<String, JsonObject>> schemas = new LinkedHashMap<>();
        for (WorkflowNode node : plan.plan().nodes()) {
            if (node instanceof HumanTaskNode task) {
                Map<String, JsonObject> decisions = new LinkedHashMap<>();
                for (HumanTaskNode.TaskDecision decision : task.decisions()) {
                    String typeName = decision.payloadTypeName();
                    if (VOID_PRIMITIVE.equals(typeName) || VOID_WRAPPER.equals(typeName)) {
                        continue;
                    }
                    decisions.put(decision.name(), schemaOf(typeName, null));
                }
                schemas.put(task.stepId(), decisions);
            }
        }
        return schemas;
    }

    private JsonObject schemaOf(String typeName, Class<?> knownType) {
        Optional<CachedPayloadSchema> cached = cache.findByTypeName(typeName);
        if (cached.isPresent()) {
            return cached.get().document().copy();
        }
        if (PayloadShapeRule.isObjectByDefinition(typeName)) {
            return PayloadShapeRule.objectByDefinitionDocument();
        }
        Class<?> type = knownType != null ? knownType : typeNames.find(typeName).orElse(null);
        if (generator == null || type == null) {
            throw new IllegalStateException("the schema module's registration validator did not run for this registry:"
                    + " install WorkflowSchemaModule in the component that provides WorkflowRegistry"
                    + " (payload type '" + typeName + "')");
        }
        return generator.describe(type);
    }
}
