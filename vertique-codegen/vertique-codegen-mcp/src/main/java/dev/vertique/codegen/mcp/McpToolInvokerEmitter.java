// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Emits one package-private {@code <DeclaringType>_<method>_McpToolInvoker} per validated tool.
 *
 * <p>The invoker is the whole dispatch mechanism for its tool: it is Dagger-constructed with the
 * application's tool bean, obtains its immutable {@code McpToolRuntime} binding once during
 * composition from {@code McpToolRuntimeFactory}, and calls the tool method <em>directly</em>.
 * Nothing is scanned, and nothing reflects — a tool the generated code cannot call directly was
 * rejected at validation.
 *
 * <p>Generated shape:
 *
 * <pre>{@code
 * @Generated("dev.vertique.codegen.mcp.McpToolProcessor")
 * @Singleton
 * class WeatherTools_lookup_McpToolInvoker implements McpToolInvoker {
 *
 *     private final WeatherTools tool;
 *     private final InputObjectProcessor inputProcessor;
 *     private final McpToolRuntime<Input> runtime;
 *
 *     @Inject
 *     WeatherTools_lookup_McpToolInvoker(
 *             WeatherTools tool, McpToolRuntimeFactory runtimes, InputObjectProcessor inputProcessor) {
 *         this.tool = tool;
 *         this.inputProcessor = inputProcessor;
 *         this.runtime = runtimes.create(
 *                 "weather.lookup", null, "…", new McpToolAnnotations(…), Input.class,
 *                 WeatherReport.class, PARAMETERS, JSON_PROFILE, new McpToolAccess(…));
 *         inputProcessor.precomputeFieldNameResolution(Input.class, runtime.fieldNameResolver());
 *     }
 *
 *     @Override public McpToolDescriptor descriptor() { return runtime.descriptor(); }
 *
 *     @Override
 *     public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
 *         Object processed;
 *         try {
 *             processed = inputProcessor.processInput(
 *                     arguments, Input.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD,
 *                     runtime.fieldNameResolver());
 *         } catch (RuntimeException sanitizationFailure) {
 *             throw new McpInputRejectionException(
 *                     "Invalid tool arguments: input sanitization failed", sanitizationFailure);
 *         }
 *
 *         Map<String, Object> normalizedArguments;
 *         Input input;
 *         try {
 *             normalizedArguments = (Map<String, Object>) processed;
 *             input = runtime.materializeArguments(normalizedArguments);
 *         } catch (IllegalArgumentException | ClassCastException malformed) {
 *             throw new McpInputRejectionException("Invalid tool arguments: materialization failed", malformed);
 *         }
 *
 *         if (!McpBeanValidation.validate(input).isEmpty()) {
 *             throw new McpInputRejectionException("Invalid tool arguments: constraint validation failed");
 *         }
 *
 *         return new PreparedCall(McpValueTrees.deepUnmodifiableMap(normalizedArguments), input);
 *     }
 *
 *     private final class PreparedCall implements McpPreparedToolCall { … tool.lookup(input.city()) … }
 *
 *     private record Input(@JsonProperty("city") String argument0) {}
 * }
 * }</pre>
 *
 * <p><strong>Carrier and metadata (T008).</strong> Every carrier component is named positionally
 * ({@code argument0}, {@code argument1}, ...) so it can never collide regardless of the declared
 * protocol names, and carries {@code @JsonProperty(protocolName)} plus the parameter's resolved
 * final REST-effective {@code @Canonicalize}/{@code @Sanitize} base chain (never {@code @Skip*}),
 * resolved at compile time by {@link McpInputPolicyResolver}. It also carries any Jakarta Bean
 * Validation constraint annotation ({@code @NotNull}, {@code @Size}, {@code @Pattern}, ...) declared
 * directly on the tool parameter — any annotation meta-annotated {@code @jakarta.validation.Constraint}
 * — copied verbatim so stage 4 ({@link #prepare} below) can enforce it against the materialized
 * carrier. A parameterized tool also emits a position-stable {@code List<McpToolParameterMetadata>}
 * pairing each component name with its external protocol name and description, and the effective
 * {@code @JsonProfile} id — resolved method-over-type — is emitted as a typed {@code JsonProfileId}
 * literal.
 *
 * <p><strong>Runtime binding and the mandatory input pipeline (contract §4.7).</strong> The
 * descriptor — including the hardened input schema and, when the tool declares structured content, the
 * output schema — is built once during composition by {@code McpToolRuntimeFactory#create}, never by
 * this invoker. Every {@code prepare()} then runs the full fixed stage order before the application
 * handler ever runs: stage 2 canonicalization/sanitization through the injected
 * {@code InputObjectProcessor} at {@code InputLocation.PAYLOAD} with the runtime's own
 * wire-to-Java field name resolver, stage 3 materialization through {@code McpToolRuntime
 * #materializeArguments}, and stage 4 Bean Validation through {@code McpBeanValidation#validate}. A
 * stage 2–4 failure is signalled by the public {@code dev.vertique.mcp.tool.McpInputRejectionException}
 * carrying a fixed, non-interpolated literal message — never a Bean Validation
 * {@code ConstraintViolation#getMessage()}, which Hibernate Validator interpolates through EL and
 * which the dispatcher would otherwise return to the wire verbatim.
 */
final class McpToolInvokerEmitter {

    // --- Generated-code type names ---

    private static final ClassName MCP_TOOL_INVOKER = ClassName.get("dev.vertique.mcp.tool", "McpToolInvoker");
    private static final ClassName MCP_TOOL_DESCRIPTOR = ClassName.get("dev.vertique.mcp.tool", "McpToolDescriptor");
    private static final ClassName MCP_TOOL_ANNOTATIONS = ClassName.get("dev.vertique.mcp.tool", "McpToolAnnotations");
    private static final ClassName MCP_TOOL_ACCESS = ClassName.get("dev.vertique.mcp.tool", "McpToolAccess");
    private static final ClassName MCP_ACCESS_MODE = ClassName.get("dev.vertique.mcp.tool", "McpAccessMode");
    private static final ClassName MCP_TOOL_RESULT = ClassName.get("dev.vertique.mcp.tool", "McpToolResult");
    private static final ClassName MCP_STRUCTURED_OUTPUT_WRITER =
            ClassName.get("dev.vertique.mcp.tool", "McpStructuredOutputWriter");
    private static final ClassName MCP_PREPARED_TOOL_CALL =
            ClassName.get("dev.vertique.mcp.tool", "McpPreparedToolCall");
    private static final ClassName MCP_CANCELLATION_SIGNAL =
            ClassName.get("dev.vertique.mcp.tool", "McpCancellationSignal");
    private static final ClassName MCP_BEAN_VALIDATION = ClassName.get("dev.vertique.mcp.tool", "McpBeanValidation");
    private static final ClassName MCP_INPUT_REJECTION_EXCEPTION =
            ClassName.get("dev.vertique.mcp.tool", "McpInputRejectionException");
    private static final ClassName ACTION_REF = ClassName.get("dev.vertique.security.authz", "ActionRef");
    private static final ClassName FUTURE = ClassName.get("io.vertx.core", "Future");
    private static final ClassName JAKARTA_INJECT = ClassName.get("jakarta.inject", "Inject");
    private static final ClassName JAKARTA_SINGLETON = ClassName.get("jakarta.inject", "Singleton");
    private static final ClassName JSON_PROFILE_ID = ClassName.get("dev.vertique.core.json", "JsonProfileId");
    private static final ClassName JSON_PROPERTY = ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty");
    private static final ClassName CANONICALIZE = ClassName.get("dev.vertique.core.sanitization", "Canonicalize");
    private static final ClassName SANITIZE = ClassName.get("dev.vertique.core.sanitization", "Sanitize");
    private static final ClassName INPUT_LOCATION = ClassName.get("dev.vertique.core.sanitization", "InputLocation");
    private static final ClassName INPUT_OBJECT_PROCESSOR =
            ClassName.get("dev.vertique.input.processing", "InputObjectProcessor");
    private static final ClassName EFFECTIVE_INPUT_POLICIES =
            ClassName.get("dev.vertique.input.processing", "EffectiveInputPolicies");
    private static final ClassName MCP_TOOL_RUNTIME_FACTORY =
            ClassName.get("dev.vertique.mcp.server.runtime", "McpToolRuntimeFactory");
    private static final ClassName MCP_TOOL_RUNTIME =
            ClassName.get("dev.vertique.mcp.server.runtime", "McpToolRuntime");
    private static final ClassName MCP_TOOL_PARAMETER_METADATA =
            ClassName.get("dev.vertique.mcp.server.runtime", "McpToolParameterMetadata");
    private static final ClassName MCP_VALUE_TREES = ClassName.get("dev.vertique.mcp.lifecycle", "McpValueTrees");

    /**
     * Jackson's super-type-token idiom, used only to capture a generic structured-output type's full
     * {@link java.lang.reflect.Type} (element type included) at compile time — mirrors {@code
     * JaxRsDescriptorEmitter#buildGenericTypeExpr}'s identical convention for a parameterized
     * {@code BODY} parameter. Jackson is already on every application's runtime classpath
     * transitively through {@code vertique-core}, so this adds no new dependency.
     */
    private static final ClassName JACKSON_TYPE_REFERENCE =
            ClassName.get("com.fasterxml.jackson.core.type", "TypeReference");

    private static final TypeName TOOL_RESULT_WILDCARD =
            ParameterizedTypeName.get(MCP_TOOL_RESULT, WildcardTypeName.subtypeOf(ClassName.OBJECT));
    private static final TypeName FUTURE_TOOL_RESULT = ParameterizedTypeName.get(FUTURE, TOOL_RESULT_WILDCARD);
    private static final TypeName ARGUMENT_MAP =
            ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ClassName.OBJECT);

    /** The fully-qualified name of the meta-annotation marking a Jakarta Bean Validation constraint. */
    private static final String JAKARTA_CONSTRAINT_FQN = "jakarta.validation.Constraint";

    private static final String INPUT_TYPE = "Input";
    private static final String PREPARED_CALL_TYPE = "PreparedCall";
    private static final String OPTIONAL_PROBE_TYPE = "OptionalProbe";
    private static final String OPTIONAL_PROBE_COMPONENT = "value";
    private static final String OPTIONAL_PROBE_WIRE_NAME = "value";

    /** The fully-qualified erasure name that identifies a {@code java.util.Optional<T>} parameter. */
    private static final String OPTIONAL_FQN = "java.util.Optional";

    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final String TOOL_FIELD = "tool";
    private static final String RUNTIME_FIELD = "runtime";
    private static final String RUNTIMES_PARAM = "runtimes";
    private static final String INPUT_PROCESSOR_FIELD = "inputProcessor";
    private static final String ARGUMENTS_PARAM = "arguments";
    private static final String CANCELLATION_PARAM = "cancellation";
    private static final String NORMALIZED_ARGUMENTS = "normalizedArguments";
    private static final String PROCESSED_VAR = "processed";
    private static final String SANITIZATION_FAILURE_VAR = "sanitizationFailure";
    private static final String MALFORMED_VAR = "malformed";
    private static final String INPUT_FIELD = "input";

    private static final String SANITIZATION_MESSAGE = "Invalid tool arguments: input sanitization failed";
    private static final String MATERIALIZATION_MESSAGE = "Invalid tool arguments: materialization failed";
    private static final String BEAN_VALIDATION_MESSAGE = "Invalid tool arguments: constraint validation failed";

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpToolInvokerEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the invoker for one validated tool into the declaring type's package.
     *
     * @param model the validated tool model; must not be {@code null}
     */
    void emit(McpToolModel model) {
        String packageName = ctx.packageNameOf(model.declaringType());
        ClassName toolType = ClassName.get(model.declaringType());
        ClassName invokerType = ClassName.get(packageName, model.invokerSimpleName());
        ClassName inputType = invokerType.nestedClass(INPUT_TYPE);
        ClassName preparedCallType = invokerType.nestedClass(PREPARED_CALL_TYPE);
        ClassName optionalProbeType = invokerType.nestedClass(OPTIONAL_PROBE_TYPE);
        boolean cancellationAware = model.parameters().stream().anyMatch(McpToolParameterModel::cancellationSignal);
        boolean optionalReaching = hasOptionalParameter(model);

        TypeSpec.Builder invoker = TypeSpec.classBuilder(invokerType)
                .addJavadoc(
                        "Generated direct invoker for the {@code $L} tool declared by {@link $T#$L}.\n",
                        model.toolName(),
                        toolType,
                        model.method().getSimpleName())
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", McpToolProcessor.PROCESSOR_FQN)
                        .build())
                .addAnnotation(JAKARTA_SINGLETON)
                .addSuperinterface(MCP_TOOL_INVOKER);

        if (model.jsonProfile() != null) {
            invoker.addField(FieldSpec.builder(JSON_PROFILE_ID, "JSON_PROFILE", Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("The effective {@code @JsonProfile} id resolved method-over-type at compile time; the\n"
                            + "server selects this profile's mapper for this tool at composition.\n")
                    .initializer("$T.of($S)", JSON_PROFILE_ID, model.jsonProfile())
                    .build());
        }

        List<McpToolParameterModel> schemaParameters = model.schemaParameters();
        if (!schemaParameters.isEmpty()) {
            invoker.addField(parameterMetadataField(schemaParameters));
        }

        invoker.addField(toolType, TOOL_FIELD, Modifier.PRIVATE, Modifier.FINAL)
                .addField(INPUT_OBJECT_PROCESSOR, INPUT_PROCESSOR_FIELD, Modifier.PRIVATE, Modifier.FINAL)
                .addField(
                        ParameterizedTypeName.get(MCP_TOOL_RUNTIME, inputType),
                        RUNTIME_FIELD,
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .addMethod(constructor(model, toolType, inputType, optionalReaching, optionalProbeType))
                .addMethod(MethodSpec.methodBuilder("descriptor")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(MCP_TOOL_DESCRIPTOR)
                        .addStatement("return $N.descriptor()", RUNTIME_FIELD)
                        .build())
                .addMethod(MethodSpec.methodBuilder("structuredOutputWriter")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ParameterizedTypeName.get(OPTIONAL, MCP_STRUCTURED_OUTPUT_WRITER))
                        .addStatement("return $T.of($N)", OPTIONAL, RUNTIME_FIELD)
                        .build())
                .addMethod(prepare(model, inputType, preparedCallType, cancellationAware))
                .addType(preparedCall(model, inputType, preparedCallType, cancellationAware))
                .addType(inputCarrier(model, inputType));

        if (optionalReaching) {
            invoker.addType(optionalProbe(optionalProbeType));
        }

        JavaFile javaFile = JavaFile.builder(packageName, invoker.build()).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(model.method(), "Failed to write %s: %s", model.invokerSimpleName(), e.getMessage());
        }
    }

    // --- Members ---

    private MethodSpec constructor(
            McpToolModel model,
            ClassName toolType,
            ClassName inputType,
            boolean optionalReaching,
            ClassName optionalProbeType) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addJavadoc(
                        "Constructs the invoker, wiring the mandatory input-processing pipeline (contract §4.7)\n"
                                + "and obtaining its immutable runtime binding once, during composition.\n\n"
                                + "@param $L the Dagger-managed type declaring the tool method\n"
                                + "@param $L builds this tool's schema-and-mapper runtime binding once, during\n"
                                + "    composition\n"
                                + "@param $L the mandatory stage-2 canonicalization/sanitization engine\n",
                        TOOL_FIELD,
                        RUNTIMES_PARAM,
                        INPUT_PROCESSOR_FIELD)
                .addAnnotation(JAKARTA_INJECT)
                .addParameter(toolType, TOOL_FIELD)
                .addParameter(MCP_TOOL_RUNTIME_FACTORY, RUNTIMES_PARAM)
                .addParameter(INPUT_OBJECT_PROCESSOR, INPUT_PROCESSOR_FIELD)
                .addStatement("this.$N = $N", TOOL_FIELD, TOOL_FIELD)
                .addStatement("this.$N = $N", INPUT_PROCESSOR_FIELD, INPUT_PROCESSOR_FIELD)
                .addStatement("this.$N = $L", RUNTIME_FIELD, runtimeCreation(model, inputType));

        if (optionalReaching) {
            // Contract §4.1: this tool has at least one Optional<T> parameter, so composition proves
            // this profile's mapper materializes Optional correctly — omitted, explicit-null, and
            // present — through the real generated OptionalProbe canary before this constructor
            // returns, i.e. before the tool can ever mount. A profile that fails this throws
            // ConfigurationException here, failing startup rather than being inferred from which
            // module ids happen to be registered.
            constructor.addStatement(
                    "$N.verifyOptionalMaterialization($T.class, $T::value)",
                    RUNTIME_FIELD,
                    optionalProbeType,
                    optionalProbeType);
        }

        constructor.addStatement(
                "$N.precomputeFieldNameResolution($T.class, $N.fieldNameResolver())",
                INPUT_PROCESSOR_FIELD,
                inputType,
                RUNTIME_FIELD);
        return constructor.build();
    }

    /**
     * Builds the {@code runtimes.create(...)} call that obtains this tool's immutable runtime binding
     * — the descriptor (hardened input schema, and output schema when the tool declares structured
     * content) and the effective-profile mapper — once during composition.
     */
    private CodeBlock runtimeCreation(McpToolModel model, ClassName inputType) {
        CodeBlock titleArg = model.title() == null ? CodeBlock.of("null") : CodeBlock.of("$S", model.title());
        CodeBlock parametersArg =
                model.schemaParameters().isEmpty() ? CodeBlock.of("$T.of()", List.class) : CodeBlock.of("PARAMETERS");
        CodeBlock jsonProfileArg = model.jsonProfile() == null ? CodeBlock.of("null") : CodeBlock.of("JSON_PROFILE");
        return CodeBlock.of(
                "$N.create($S, $L, $S, new $T($L, $L, $L, $L), $T.class, $L, $L, $L, $L)",
                RUNTIMES_PARAM,
                model.toolName(),
                titleArg,
                model.description(),
                MCP_TOOL_ANNOTATIONS,
                model.readOnlyHint(),
                model.destructiveHint(),
                model.idempotentHint(),
                model.openWorldHint(),
                inputType,
                structuredOutputTypeExpr(model),
                parametersArg,
                jsonProfileArg,
                access(model));
    }

    private CodeBlock access(McpToolModel model) {
        CodeBlock roles = model.roles().isEmpty()
                ? CodeBlock.of("$T.of()", List.class)
                : CodeBlock.of(
                        "$T.of($L)",
                        List.class,
                        model.roles().stream()
                                .map(role -> CodeBlock.of("$S", role))
                                .collect(CodeBlock.joining(", ")));
        CodeBlock action = model.action() == null
                ? CodeBlock.of("null")
                : CodeBlock.of("$T.parse($S)", ACTION_REF, model.action());
        return CodeBlock.of(
                "new $T($T.$L, $L, $L)",
                MCP_TOOL_ACCESS,
                MCP_ACCESS_MODE,
                model.accessMode().name(),
                roles,
                action);
    }

    private MethodSpec prepare(
            McpToolModel model, ClassName inputType, ClassName preparedCallType, boolean cancellationAware) {
        MethodSpec.Builder prepare = MethodSpec.methodBuilder("prepare")
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addModifiers(Modifier.PUBLIC)
                .returns(MCP_PREPARED_TOOL_CALL)
                .addParameter(ARGUMENT_MAP, ARGUMENTS_PARAM)
                .addParameter(MCP_CANCELLATION_SIGNAL, CANCELLATION_PARAM);

        // Stage 2 — INP-001 canonicalization/sanitization at InputLocation.PAYLOAD (contract §4.7
        // point 2). The generated invocation-level literal is always EffectiveInputPolicies.NONE
        // (§4.1); declared per-field policies are discovered reflectively from the carrier's own
        // annotations, not passed as invocation-level policies.
        prepare.addStatement("$T $N", ClassName.OBJECT, PROCESSED_VAR);
        prepare.beginControlFlow("try");
        prepare.addStatement(
                "$N = $N.processInput($N, $T.class, $T.NONE, $T.PAYLOAD, $N.fieldNameResolver())",
                PROCESSED_VAR,
                INPUT_PROCESSOR_FIELD,
                ARGUMENTS_PARAM,
                inputType,
                EFFECTIVE_INPUT_POLICIES,
                INPUT_LOCATION,
                RUNTIME_FIELD);
        prepare.nextControlFlow("catch ($T $N)", RuntimeException.class, SANITIZATION_FAILURE_VAR);
        prepare.addStatement(
                "throw new $T($S, $N)", MCP_INPUT_REJECTION_EXCEPTION, SANITIZATION_MESSAGE, SANITIZATION_FAILURE_VAR);
        prepare.endControlFlow();

        // Stage 3 — materialization through the effective mapper (contract §4.7 point 3). The cast is
        // guarded here too: a wrong-typed processed tree must settle as the bounded rejection below,
        // never propagate as an uncaught ClassCastException the dispatcher would misclassify as an
        // internal server fault.
        prepare.addStatement("$T $N", ARGUMENT_MAP, NORMALIZED_ARGUMENTS);
        prepare.addStatement("$T $N", inputType, INPUT_FIELD);
        prepare.beginControlFlow("try");
        prepare.addStatement("$N = ($T) $N", NORMALIZED_ARGUMENTS, ARGUMENT_MAP, PROCESSED_VAR);
        prepare.addStatement("$N = $N.materializeArguments($N)", INPUT_FIELD, RUNTIME_FIELD, NORMALIZED_ARGUMENTS);
        prepare.nextControlFlow(
                "catch ($T | $T $N)", IllegalArgumentException.class, ClassCastException.class, MALFORMED_VAR);
        prepare.addStatement(
                "throw new $T($S, $N)", MCP_INPUT_REJECTION_EXCEPTION, MATERIALIZATION_MESSAGE, MALFORMED_VAR);
        prepare.endControlFlow();

        // Stage 4 — Bean Validation on the materialized carrier (contract §4.7 point 4). The rejection
        // message is a fixed literal, never a ConstraintViolation#getMessage(): Hibernate Validator
        // interpolates message templates through EL, and the dispatcher returns this text verbatim.
        prepare.beginControlFlow("if (!$T.validate($N).isEmpty())", MCP_BEAN_VALIDATION, INPUT_FIELD);
        prepare.addStatement("throw new $T($S)", MCP_INPUT_REJECTION_EXCEPTION, BEAN_VALIDATION_MESSAGE);
        prepare.endControlFlow();

        // McpValueTrees.deepUnmodifiableMap, not Map.copyOf: INP-001 deliberately preserves an
        // explicit-null Optional<T> argument all the way through materialization, and Map.copyOf
        // throws NPE on a null value; it also only freezes the root map, leaving nested Map/List
        // values mutable, which violates McpPreparedToolCall#normalizedArguments()'s deeply-immutable
        // contract.
        prepare.addStatement(
                "return new $T($T.deepUnmodifiableMap($N), $N$L)",
                preparedCallType,
                MCP_VALUE_TREES,
                NORMALIZED_ARGUMENTS,
                INPUT_FIELD,
                cancellationAware ? CodeBlock.of(", $N", CANCELLATION_PARAM) : CodeBlock.of(""));
        return prepare.build();
    }

    private TypeSpec preparedCall(
            McpToolModel model, ClassName inputType, ClassName preparedCallType, boolean cancellationAware) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addParameter(ARGUMENT_MAP, NORMALIZED_ARGUMENTS)
                .addParameter(inputType, INPUT_FIELD)
                .addStatement("this.$N = $N", NORMALIZED_ARGUMENTS, NORMALIZED_ARGUMENTS)
                .addStatement("this.$N = $N", INPUT_FIELD, INPUT_FIELD);

        TypeSpec.Builder prepared = TypeSpec.classBuilder(preparedCallType)
                .addJavadoc("The prepared call for one {@code $L} invocation.\n", model.toolName())
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .addSuperinterface(MCP_PREPARED_TOOL_CALL)
                .addField(ARGUMENT_MAP, NORMALIZED_ARGUMENTS, Modifier.PRIVATE, Modifier.FINAL)
                .addField(inputType, INPUT_FIELD, Modifier.PRIVATE, Modifier.FINAL);

        if (cancellationAware) {
            prepared.addField(MCP_CANCELLATION_SIGNAL, CANCELLATION_PARAM, Modifier.PRIVATE, Modifier.FINAL);
            constructor
                    .addParameter(MCP_CANCELLATION_SIGNAL, CANCELLATION_PARAM)
                    .addStatement("this.$N = $N", CANCELLATION_PARAM, CANCELLATION_PARAM);
        }

        return prepared.addMethod(constructor.build())
                .addMethod(MethodSpec.methodBuilder(NORMALIZED_ARGUMENTS)
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ARGUMENT_MAP)
                        .addStatement("return $N", NORMALIZED_ARGUMENTS)
                        .build())
                .addMethod(MethodSpec.methodBuilder("invoke")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(FUTURE_TOOL_RESULT)
                        .addStatement(invocation(model))
                        .build())
                .build();
    }

    /**
     * Builds the direct invocation statement for one tool, adapting the declared handler shape to
     * {@code Future<McpToolResult<?>>}. The tool method is always called by name on the injected
     * bean.
     */
    private CodeBlock invocation(McpToolModel model) {
        CodeBlock call = CodeBlock.of(
                "$N.$L($L)",
                TOOL_FIELD,
                model.method().getSimpleName(),
                model.parameters().stream()
                        .map(parameter -> parameter.cancellationSignal()
                                ? CodeBlock.of("$N", CANCELLATION_PARAM)
                                : CodeBlock.of("$N.$L()", INPUT_FIELD, parameter.componentName()))
                        .collect(CodeBlock.joining(", ")));

        return switch (model.returnModel().shape()) {
            case FUTURE_TOOL_RESULT -> CodeBlock.of("return $L.map(result -> ($T) result)", call, TOOL_RESULT_WILDCARD);
            case TOOL_RESULT -> CodeBlock.of("return $T.succeededFuture(($T) $L)", FUTURE, TOOL_RESULT_WILDCARD, call);
            case FUTURE_VALUE ->
                CodeBlock.of(
                        "return $L.map(value -> ($T) $T.$L(value))",
                        call,
                        TOOL_RESULT_WILDCARD,
                        MCP_TOOL_RESULT,
                        contentFactory(model));
            case VALUE ->
                CodeBlock.of(
                        "return $T.succeededFuture(($T) $T.$L($L))",
                        FUTURE,
                        TOOL_RESULT_WILDCARD,
                        MCP_TOOL_RESULT,
                        contentFactory(model),
                        call);
        };
    }

    /**
     * Chooses the tool-content factory for a plain handler result: a {@code String} is one text
     * content item, and any other value is structured content.
     */
    private String contentFactory(McpToolModel model) {
        return isTextResult(model) ? "text" : "structured";
    }

    /**
     * Returns {@code true} when the tool's result type erases to {@code String} — a text-only result
     * with no structured-output schema.
     */
    private boolean isTextResult(McpToolModel model) {
        return ctx.types().erasure(model.returnModel().resultType()).toString().equals(String.class.getName());
    }

    /**
     * Builds the {@code structuredOutputType} argument to {@code McpToolRuntimeFactory#create}: the
     * {@code null} literal for a text-only tool, and otherwise a full {@link java.lang.reflect.Type}
     * token for the structured result — {@code Foo.class} for a non-generic type, or, for a
     * parameterized type (e.g. {@code List<Foo>}), an anonymous Jackson {@code TypeReference}
     * super-type-token literal ({@code new TypeReference<List<Foo>>() {}.getType()}) that captures the
     * element type too. {@code McpToolRuntimeFactory#create} declares this parameter {@code
     * java.lang.reflect.Type}, not {@code Class<?>}, specifically so a generic structured result's
     * advertised output schema — and output-schema validation — can describe its element type instead
     * of erasing to the raw container ({@code List.class}). Mirrors {@code
     * JaxRsDescriptorEmitter#buildGenericTypeExpr}'s identical convention for a parameterized
     * {@code BODY} parameter.
     */
    private CodeBlock structuredOutputTypeExpr(McpToolModel model) {
        if (isTextResult(model)) {
            return CodeBlock.of("null");
        }
        TypeMirror resultType = model.returnModel().resultType();
        if (resultType instanceof DeclaredType declared
                && !declared.getTypeArguments().isEmpty()) {
            return CodeBlock.of("new $T<$L>() {}.getType()", JACKSON_TYPE_REFERENCE, genericSourceTypeName(resultType));
        }
        return CodeBlock.of("$T.class", TypeName.get(ctx.types().erasure(resultType)));
    }

    /**
     * Returns the erased-generic <b>source</b>-form name of {@code type}, suitable for interpolation
     * into a generated {@code TypeReference<...>} literal (a binary {@code Outer$Inner} form would not
     * compile there). A parameterized type recurses into its own type arguments so nested generics
     * (e.g. {@code Map<String, List<Foo>>}) render fully; a raw or non-declared type falls back to its
     * erasure's own {@code toString()}.
     *
     * @param type the type mirror to render
     * @return the source-form type name string
     */
    private String genericSourceTypeName(TypeMirror type) {
        if (type instanceof ArrayType arrayType) {
            return genericSourceTypeName(arrayType.getComponentType()) + "[]";
        }
        if (!(type instanceof DeclaredType declared)
                || declared.getTypeArguments().isEmpty()) {
            return ctx.types().erasure(type).toString();
        }
        Element rawElement = ctx.types().asElement(ctx.types().erasure(declared));
        String rawName = rawElement instanceof TypeElement te
                ? te.getQualifiedName().toString()
                : ctx.types().erasure(declared).toString();
        StringBuilder sb = new StringBuilder(rawName).append('<');
        List<? extends TypeMirror> args = declared.getTypeArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(genericSourceTypeName(args.get(i)));
        }
        return sb.append('>').toString();
    }

    /**
     * Builds the {@code PARAMETERS} field: the position-stable, immutable
     * {@code List<McpToolParameterMetadata>} carrying each parameter's collision-safe carrier
     * component name, its external protocol name, and its description.
     */
    private FieldSpec parameterMetadataField(List<McpToolParameterModel> schemaParameters) {
        TypeName parametersType = ParameterizedTypeName.get(ClassName.get(List.class), MCP_TOOL_PARAMETER_METADATA);
        CodeBlock entries = schemaParameters.stream()
                .map(parameter -> CodeBlock.of(
                        "new $T($S, $S, $S)",
                        MCP_TOOL_PARAMETER_METADATA,
                        parameter.componentName(),
                        parameter.protocolName(),
                        parameter.description()))
                .collect(CodeBlock.joining(", "));
        return FieldSpec.builder(parametersType, "PARAMETERS", Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The position-stable, declaration-ordered parameter metadata: each carrier component name\n"
                        + "paired with its external protocol name and description.\n")
                .initializer("$T.of($L)", List.class, entries)
                .build();
    }

    private TypeSpec inputCarrier(McpToolModel model, ClassName inputType) {
        MethodSpec.Builder components = MethodSpec.constructorBuilder();
        model.schemaParameters().forEach(parameter -> {
            ParameterSpec.Builder component = ParameterSpec.builder(
                            TypeName.get(parameter.type()), parameter.componentName())
                    .addAnnotation(AnnotationSpec.builder(JSON_PROPERTY)
                            .addMember("value", "$S", parameter.protocolName())
                            .build());
            addPolicyAnnotation(component, CANONICALIZE, parameter.canonicalizers());
            addPolicyAnnotation(component, SANITIZE, parameter.sanitizers());
            addConstraintAnnotations(component, parameter);
            components.addParameter(component.build());
        });

        return TypeSpec.recordBuilder(inputType)
                .addJavadoc(
                        "The typed input carrier for {@code $L}: one component per input-schema member, named with a\n"
                                + "collision-safe positional Java identifier ({@code argument0}, {@code argument1}, ...)\n"
                                + "while {@code @JsonProperty} preserves the declared protocol name on the wire, and any\n"
                                + "Jakarta Bean Validation constraint declared on the source parameter is carried through\n"
                                + "for stage 4 of the fixed request-time input pipeline (contract §4.7).\n",
                        model.toolName())
                .addModifiers(Modifier.PRIVATE)
                .recordConstructor(components.build())
                .build();
    }

    /**
     * Adds a {@code @Canonicalize}/{@code @Sanitize} annotation carrying the resolved chain to the
     * given carrier component, when the chain is non-empty. JavaPoet renders repeated same-name
     * members as a {@code {A.class, B.class}} array automatically.
     */
    private void addPolicyAnnotation(
            ParameterSpec.Builder component, ClassName policyAnnotation, List<TypeMirror> chain) {
        if (chain.isEmpty()) {
            return;
        }
        AnnotationSpec.Builder annotation = AnnotationSpec.builder(policyAnnotation);
        chain.forEach(type -> annotation.addMember("value", "$T.class", TypeName.get(type)));
        component.addAnnotation(annotation.build());
    }

    /**
     * Copies every Jakarta Bean Validation constraint annotation declared directly on the source tool
     * parameter onto the generated carrier component, verbatim — a declared annotation is recognized
     * as a constraint the same way Jakarta Validation itself does: meta-annotated
     * {@code @jakarta.validation.Constraint}, not a hardcoded {@code jakarta.validation.constraints.*}
     * allowlist, so a custom application constraint is copied too. Without this, stage 4
     * ({@code McpBeanValidation#validate}) would always see a carrier with no declared constraints,
     * since the generated {@code Input} record is otherwise unrelated to the source method parameter.
     */
    private void addConstraintAnnotations(ParameterSpec.Builder component, McpToolParameterModel parameter) {
        for (AnnotationMirror mirror : parameter.element().getAnnotationMirrors()) {
            TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (AnnotationMirrors.isPresent(annotationType, JAKARTA_CONSTRAINT_FQN)) {
                component.addAnnotation(AnnotationSpec.get(mirror));
            }
        }
    }

    // --- OptionalProbe canary (contract §4.1, issue #428) ---

    /**
     * Returns {@code true} when {@code model} declares at least one schema parameter whose erasure is
     * {@code java.util.Optional} — i.e. this tool is "Optional-reaching" and composition must prove
     * this profile's mapper materializes {@code Optional} correctly before the tool can mount.
     */
    private boolean hasOptionalParameter(McpToolModel model) {
        return model.schemaParameters().stream().anyMatch(this::isOptional);
    }

    /** Returns {@code true} when {@code parameter}'s declared type erases to {@code java.util.Optional}. */
    private boolean isOptional(McpToolParameterModel parameter) {
        return ctx.types().erasure(parameter.type()).toString().equals(OPTIONAL_FQN);
    }

    /**
     * Builds the generated {@code OptionalProbe} canary record (contract §4.1, frozen inventory row):
     * a private, same-package record whose sole component is an {@code Optional<String>}, materialized
     * by {@code McpToolRuntime#verifyOptionalMaterialization} once during composition with omitted,
     * explicit-null, and present cases — before this tool can mount. Its shape is fixed and
     * independent of the tool's own Optional-typed parameter's element type: the canary proves the
     * effective profile mapper's <em>Optional</em>-materialization capability, which {@code
     * jackson-datatype-jdk8} governs generically for any element type, not a property of the specific
     * contained type.
     */
    private TypeSpec optionalProbe(ClassName optionalProbeType) {
        return TypeSpec.recordBuilder(optionalProbeType)
                .addJavadoc(
                        "Generated per-profile {@code Optional} materialization canary (contract §4.1). Composition\n"
                                + "materializes this record with omitted, explicit-null, and present {@code $L} cases\n"
                                + "through the same effective profile mapper that materializes this tool's real {@code $L}\n"
                                + "carrier, and fails startup if any case does not resolve the way an {@code Optional<T>}\n"
                                + "tool parameter's contract requires — never inferred from which module ids are\n"
                                + "registered.\n",
                        OPTIONAL_PROBE_COMPONENT,
                        INPUT_TYPE)
                .addModifiers(Modifier.PRIVATE)
                .recordConstructor(MethodSpec.constructorBuilder()
                        .addParameter(ParameterSpec.builder(
                                        ParameterizedTypeName.get(OPTIONAL, ClassName.get(String.class)),
                                        OPTIONAL_PROBE_COMPONENT)
                                .addAnnotation(AnnotationSpec.builder(JSON_PROPERTY)
                                        .addMember("value", "$S", OPTIONAL_PROBE_WIRE_NAME)
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
