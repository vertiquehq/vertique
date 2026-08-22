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
import dev.vertique.codegen.CodegenContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Emits one package-private {@code <DeclaringType>_<method>_McpToolInvoker} per validated tool.
 *
 * <p>The invoker is the whole dispatch mechanism for its tool: it is Dagger-constructed with the
 * application's tool bean, builds its immutable {@code McpToolDescriptor} once during composition,
 * and calls the tool method <em>directly</em>. Nothing is scanned, and nothing reflects — a tool the
 * generated code cannot call directly was rejected at validation.
 *
 * <p>Generated shape:
 *
 * <pre>{@code
 * @Generated("dev.vertique.codegen.mcp.McpToolProcessor")
 * @Singleton
 * class WeatherTools_lookup_McpToolInvoker implements McpToolInvoker {
 *
 *     private static final String INPUT_SCHEMA = "{\"type\":\"object\"}";
 *
 *     private final WeatherTools tool;
 *     private final McpToolDescriptor descriptor;
 *
 *     @Inject
 *     WeatherTools_lookup_McpToolInvoker(WeatherTools tool) {
 *         this.tool = tool;
 *         this.descriptor = new McpToolDescriptor(…, new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
 *     }
 *
 *     @Override public McpToolDescriptor descriptor() { return descriptor; }
 *
 *     @Override
 *     public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
 *         Map<String, Object> normalizedArguments = Map.copyOf(arguments);
 *         Input input = new Input((String) normalizedArguments.get("city"));
 *         return new PreparedCall(normalizedArguments, input);
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
 * resolved at compile time by {@link McpInputPolicyResolver}. A parameterized tool also emits a
 * position-stable {@code List<McpToolParameterMetadata>} pairing each component name with its
 * external protocol name and description, and the effective {@code @JsonProfile} id — resolved
 * method-over-type — is emitted as a typed {@code JsonProfileId} literal.
 *
 * <p><strong>Composition-time scope.</strong> This slice emits the carrier, metadata, and profile
 * literal shape above; it generates no schema and compiles no validator. The input schema is still
 * the minimal object schema and argument materialization is still a direct carrier construction —
 * the profile-aware schema synthesis, input-policy application, and Bean Validation that
 * {@code prepare(...)} owns arrive with the shared schema and call-pipeline slices, which replace
 * {@link #INPUT_SCHEMA_PLACEHOLDER} and the carrier construction below.
 */
final class McpToolInvokerEmitter {

    /**
     * The input schema emitted before profile-aware schema synthesis exists: a well-formed,
     * canonical, non-blank object schema that satisfies the descriptor's invariants.
     */
    static final String INPUT_SCHEMA_PLACEHOLDER = "{\"type\":\"object\"}";

    // --- Generated-code type names ---

    private static final ClassName MCP_TOOL_INVOKER = ClassName.get("dev.vertique.mcp.tool", "McpToolInvoker");
    private static final ClassName MCP_TOOL_DESCRIPTOR = ClassName.get("dev.vertique.mcp.tool", "McpToolDescriptor");
    private static final ClassName MCP_TOOL_ANNOTATIONS = ClassName.get("dev.vertique.mcp.tool", "McpToolAnnotations");
    private static final ClassName MCP_TOOL_ACCESS = ClassName.get("dev.vertique.mcp.tool", "McpToolAccess");
    private static final ClassName MCP_ACCESS_MODE = ClassName.get("dev.vertique.mcp.tool", "McpAccessMode");
    private static final ClassName MCP_TOOL_RESULT = ClassName.get("dev.vertique.mcp.tool", "McpToolResult");
    private static final ClassName MCP_PREPARED_TOOL_CALL =
            ClassName.get("dev.vertique.mcp.tool", "McpPreparedToolCall");
    private static final ClassName MCP_CANCELLATION_SIGNAL =
            ClassName.get("dev.vertique.mcp.tool", "McpCancellationSignal");
    private static final ClassName ACTION_REF = ClassName.get("dev.vertique.security.authz", "ActionRef");
    private static final ClassName FUTURE = ClassName.get("io.vertx.core", "Future");
    private static final ClassName JAKARTA_INJECT = ClassName.get("jakarta.inject", "Inject");
    private static final ClassName JAKARTA_SINGLETON = ClassName.get("jakarta.inject", "Singleton");
    private static final ClassName JSON_PROFILE_ID = ClassName.get("dev.vertique.core.json", "JsonProfileId");
    private static final ClassName JSON_PROPERTY = ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty");
    private static final ClassName CANONICALIZE = ClassName.get("dev.vertique.core.sanitization", "Canonicalize");
    private static final ClassName SANITIZE = ClassName.get("dev.vertique.core.sanitization", "Sanitize");
    private static final ClassName MCP_TOOL_PARAMETER_METADATA =
            ClassName.get("dev.vertique.mcp.server.runtime", "McpToolParameterMetadata");

    private static final TypeName TOOL_RESULT_WILDCARD =
            ParameterizedTypeName.get(MCP_TOOL_RESULT, WildcardTypeName.subtypeOf(ClassName.OBJECT));
    private static final TypeName FUTURE_TOOL_RESULT = ParameterizedTypeName.get(FUTURE, TOOL_RESULT_WILDCARD);
    private static final TypeName ARGUMENT_MAP =
            ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ClassName.OBJECT);

    private static final String INPUT_TYPE = "Input";
    private static final String PREPARED_CALL_TYPE = "PreparedCall";
    private static final String TOOL_FIELD = "tool";
    private static final String DESCRIPTOR_FIELD = "descriptor";
    private static final String ARGUMENTS_PARAM = "arguments";
    private static final String CANCELLATION_PARAM = "cancellation";
    private static final String NORMALIZED_ARGUMENTS = "normalizedArguments";
    private static final String INPUT_FIELD = "input";

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
        boolean cancellationAware = model.parameters().stream().anyMatch(McpToolParameterModel::cancellationSignal);

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
                .addSuperinterface(MCP_TOOL_INVOKER)
                .addField(FieldSpec.builder(
                                String.class, "INPUT_SCHEMA", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", INPUT_SCHEMA_PLACEHOLDER)
                        .build());

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
                .addField(MCP_TOOL_DESCRIPTOR, DESCRIPTOR_FIELD, Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(constructor(model, toolType))
                .addMethod(MethodSpec.methodBuilder("descriptor")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(MCP_TOOL_DESCRIPTOR)
                        .addStatement("return $N", DESCRIPTOR_FIELD)
                        .build())
                .addMethod(prepare(model, inputType, preparedCallType, cancellationAware))
                .addType(preparedCall(model, inputType, preparedCallType, cancellationAware))
                .addType(inputCarrier(model, inputType));

        JavaFile javaFile = JavaFile.builder(packageName, invoker.build()).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(model.method(), "Failed to write %s: %s", model.invokerSimpleName(), e.getMessage());
        }
    }

    // --- Members ---

    private MethodSpec constructor(McpToolModel model, ClassName toolType) {
        return MethodSpec.constructorBuilder()
                .addJavadoc(
                        "Constructs the invoker and builds its immutable descriptor once, during composition.\n\n"
                                + "@param $L the Dagger-managed type declaring the tool method\n",
                        TOOL_FIELD)
                .addAnnotation(JAKARTA_INJECT)
                .addParameter(toolType, TOOL_FIELD)
                .addStatement("this.$N = $N", TOOL_FIELD, TOOL_FIELD)
                .addStatement(
                        "this.$N = new $T($S, $L, $S, new $T($L, $L, $L, $L), INPUT_SCHEMA, null, $L)",
                        DESCRIPTOR_FIELD,
                        MCP_TOOL_DESCRIPTOR,
                        model.toolName(),
                        model.title() == null ? CodeBlock.of("null") : CodeBlock.of("$S", model.title()),
                        model.description(),
                        MCP_TOOL_ANNOTATIONS,
                        model.readOnlyHint(),
                        model.destructiveHint(),
                        model.idempotentHint(),
                        model.openWorldHint(),
                        access(model))
                .build();
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
        List<McpToolParameterModel> schemaParameters = model.schemaParameters();

        MethodSpec.Builder prepare = MethodSpec.methodBuilder("prepare")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(MCP_PREPARED_TOOL_CALL)
                .addParameter(ARGUMENT_MAP, ARGUMENTS_PARAM)
                .addParameter(MCP_CANCELLATION_SIGNAL, CANCELLATION_PARAM);

        if (schemaParameters.stream().anyMatch(parameter -> isParameterized(parameter.type()))) {
            prepare.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }

        prepare.addStatement("$T $N = $T.copyOf($N)", ARGUMENT_MAP, NORMALIZED_ARGUMENTS, Map.class, ARGUMENTS_PARAM);
        CodeBlock components = schemaParameters.stream()
                .map(parameter -> CodeBlock.of(
                        "($T) $N.get($S)",
                        TypeName.get(parameter.type()).box(),
                        NORMALIZED_ARGUMENTS,
                        parameter.protocolName()))
                .collect(CodeBlock.joining(", "));
        prepare.addStatement("$T $N = new $T($L)", inputType, INPUT_FIELD, inputType, components);
        prepare.addStatement(
                "return new $T($N, $N$L)",
                preparedCallType,
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
        return ctx.types().erasure(model.returnModel().resultType()).toString().equals(String.class.getName())
                ? "text"
                : "structured";
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
            components.addParameter(component.build());
        });

        return TypeSpec.recordBuilder(inputType)
                .addJavadoc(
                        "The typed input carrier for {@code $L}: one component per input-schema member, named with a\n"
                                + "collision-safe positional Java identifier ({@code argument0}, {@code argument1}, ...)\n"
                                + "while {@code @JsonProperty} preserves the declared protocol name on the wire.\n",
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

    private boolean isParameterized(TypeMirror type) {
        return type instanceof DeclaredType declared
                && !declared.getTypeArguments().isEmpty();
    }
}
