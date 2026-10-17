// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.schema.validation.JsonSchemaValidationOptions;
import dev.vertique.json.schema.validation.PatternCompileFailure;
import dev.vertique.json.schema.validation.PatternInputGuard;
import dev.vertique.json.schema.validation.SchemaPatterns;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registration-time check of the payload types a candidate workflow definition declares.
 *
 * <p>For the start payload type and then for the decision payload types of every human task, in plan order, each
 * distinct type name is resolved once per process JSON profile: {@code void} types mean no payload and are skipped; a
 * cached type is reused; the JSON object types are described by a fixed document; any other type is loaded with the
 * validator's class loader, described by the profile's schema generator, required to be object-shaped, checked for
 * patterns that do not compile, compiled into a guarded validator and cached. The first failure ends validation and
 * is reported as a {@link WorkflowDefinitionException}; a failure is never cached, while entries resolved before it
 * stay cached. A configuration fault, an unknown system profile, propagates unchanged.
 *
 * <p>The candidate is only read: the validator never consults the workflow registry. Exception messages name the
 * definition, the declaration site, the type and the shape of the failure; they never carry a payload value.
 */
@Singleton
final class PayloadSchemaRegistrationValidator implements WorkflowDefinitionValidator {

    private static final String VOID_PRIMITIVE = "void";
    private static final String VOID_WRAPPER = Void.class.getName();

    private final PayloadSchemaCache cache;
    private final PayloadSchemaGenerator generator;
    private final ClassLoader classLoader;

    @Inject
    PayloadSchemaRegistrationValidator(
            JsonMapperProfileRegistry profileRegistry,
            JsonConfig jsonConfig,
            Optional<Validator> validator,
            PayloadSchemaCache cache) {
        this(
                cache,
                new PayloadSchemaGenerator(profileRegistry, jsonConfig, validator),
                PayloadSchemaRegistrationValidator.class.getClassLoader());
    }

    PayloadSchemaRegistrationValidator(
            PayloadSchemaCache cache, PayloadSchemaGenerator generator, ClassLoader classLoader) {
        this.cache = cache;
        this.generator = generator;
        this.classLoader = classLoader;
    }

    @Override
    public void validate(RegisteredWorkflowDefinition definition) {
        String definitionId = definition.plan().definitionId();
        long version = definition.plan().definitionVersion();

        Class<?> startType = definition.startPayloadType();
        if (startType != null && startType != void.class && startType != Void.class) {
            resolve(
                    new PayloadShapeRule.Context(definitionId, version, null, null, startType.getName()),
                    () -> startType);
        }
        for (WorkflowNode node : definition.plan().nodes()) {
            if (node instanceof HumanTaskNode task) {
                for (HumanTaskNode.TaskDecision decision : task.decisions()) {
                    String typeName = decision.payloadTypeName();
                    if (VOID_PRIMITIVE.equals(typeName) || VOID_WRAPPER.equals(typeName)) {
                        continue;
                    }
                    PayloadShapeRule.Context context = new PayloadShapeRule.Context(
                            definitionId, version, task.stepId(), decision.name(), typeName);
                    resolve(context, () -> load(context));
                }
            }
        }
    }

    private void resolve(PayloadShapeRule.Context context, Supplier<Class<?>> type) {
        SchemaKey key = new SchemaKey(context.typeName(), generator.profileId());
        if (cache.find(key).isEmpty()) {
            cache.computeIfAbsent(key, ignored -> build(context, type));
        }
    }

    private CachedPayloadSchema build(PayloadShapeRule.Context context, Supplier<Class<?>> type) {
        JsonObject document;
        if (PayloadShapeRule.isObjectByDefinition(context.typeName())) {
            document = PayloadShapeRule.objectByDefinitionDocument();
        } else {
            document = describe(context, type.get());
            PayloadShapeRule.check(document, context);
        }
        failOnUncompilablePattern(document, context);
        return compile(document, context);
    }

    private Class<?> load(PayloadShapeRule.Context context) {
        try {
            return Class.forName(context.typeName(), false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new WorkflowDefinitionException(context.subject() + " cannot be loaded", e);
        }
    }

    private JsonObject describe(PayloadShapeRule.Context context, Class<?> type) {
        try {
            return generator.describe(type);
        } catch (JsonProfileConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new WorkflowDefinitionException(context.subject() + " cannot be described: " + reason, e);
        }
    }

    private static void failOnUncompilablePattern(JsonObject document, PayloadShapeRule.Context context) {
        List<PatternCompileFailure> failures = SchemaPatterns.precompile(document);
        if (!failures.isEmpty()) {
            PatternCompileFailure first = failures.get(0);
            throw new WorkflowDefinitionException(context.subject() + " has a pattern at " + first.pointer()
                    + " that does not compile: " + first.description() + " near index " + first.index());
        }
    }

    private static CachedPayloadSchema compile(JsonObject document, PayloadShapeRule.Context context) {
        PatternInputGuard guard =
                new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS);
        try {
            return new CachedPayloadSchema(
                    document, guard.compile(document, JsonSchemaValidationOptions.draft202012()), guard);
        } catch (RuntimeException e) {
            throw new WorkflowDefinitionException(
                    context.subject() + " cannot be described: the schema cannot be compiled", e);
        }
    }
}
