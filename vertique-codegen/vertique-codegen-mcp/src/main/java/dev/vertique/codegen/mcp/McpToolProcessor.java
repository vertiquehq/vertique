// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that turns {@code @McpTool}-annotated methods into generated MCP tool
 * registry source.
 *
 * <p>The generation contract is: one package-private
 * {@code <DeclaringType>_<method>_McpToolInvoker} per tool — holding a typed {@code Input} carrier
 * record, an immutable {@code McpToolDescriptor} built once at composition, and a direct call to the
 * application method — plus a single {@code GeneratedMcpToolsModule} Dagger module that multibinds
 * every emitted invoker and its descriptor. No runtime scanning and no reflective invocation
 * fallback exist; a tool declaration that cannot be invoked directly is a compile error rather than
 * a degraded binding.
 *
 * <p>Processing is a single pass over the first non-empty round:
 *
 * <ol>
 *   <li>group the round's tool methods by declaring type, ordered by declaring type and method name
 *       so the emitted source is byte-identical across builds of the same inputs;</li>
 *   <li>validate each declaring type once ({@link McpToolModelValidator#validateDeclaringType}) and
 *       then each tool method, building a {@link McpToolModel} only for a tool that passed every
 *       boundary;</li>
 *   <li>reject duplicate tool names across the whole compilation;</li>
 *   <li>emit nothing at all when any diagnostic was reported — a partial registry would bind a
 *       subset of the application's tools — and otherwise emit one invoker per tool plus the single
 *       module.</li>
 * </ol>
 */
@SupportedAnnotationTypes(McpToolProcessor.MCP_TOOL)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class McpToolProcessor extends AbstractProcessor {

    /** This processor's own fully-qualified name, stamped into every generated file. */
    static final String PROCESSOR_FQN = "dev.vertique.codegen.mcp.McpToolProcessor";

    /** The tool annotation this processor claims. */
    static final String MCP_TOOL = "dev.vertique.mcp.annotation.McpTool";

    /** The parameter annotation carrying protocol names and descriptions. */
    static final String MCP_TOOL_PARAM = "dev.vertique.mcp.annotation.McpToolParam";

    /** The only framework-supplied tool parameter; excluded from the input schema. */
    static final String MCP_CANCELLATION_SIGNAL = "dev.vertique.mcp.tool.McpCancellationSignal";

    /** The framework-owned rich tool result a handler may return directly. */
    static final String MCP_TOOL_RESULT = "dev.vertique.mcp.tool.McpToolResult";

    /** The JSON profile annotation resolved method-over-type. */
    static final String JSON_PROFILE = "dev.vertique.core.json.JsonProfile";

    /** The asynchronous result wrapper a handler may return. */
    static final String FUTURE = "io.vertx.core.Future";

    private CodegenContext ctx;
    private McpToolModelValidator validator;
    private McpToolInvokerEmitter invokerEmitter;
    private McpToolsModuleEmitter moduleEmitter;
    private boolean processed;

    /**
     * Constructs a new {@code McpToolProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public McpToolProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the shared {@link CodegenContext}, the validator, and both emitters.
     *
     * @param env the processing environment provided by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        validator = new McpToolModelValidator(ctx);
        invokerEmitter = new McpToolInvokerEmitter(ctx);
        moduleEmitter = new McpToolsModuleEmitter(ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates and emits in the first round that reports tools; later rounds are no-ops because
     * generated source declares no further tools.
     *
     * @param annotations the annotation types being processed
     * @param round       the current round environment
     * @return {@code false} always, so other processors continue to see the annotated elements
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver() || processed) {
            return false;
        }
        TypeElement toolAnnotation = ctx.elements().getTypeElement(MCP_TOOL);
        if (toolAnnotation == null) {
            return false;
        }
        Set<? extends Element> annotated = round.getElementsAnnotatedWith(toolAnnotation);
        if (annotated.isEmpty()) {
            return false;
        }
        processed = true;

        boolean valid = true;
        List<ExecutableElement> toolMethods = new ArrayList<>();
        for (Element element : annotated) {
            if (!(element instanceof ExecutableElement method)) {
                ctx.diagnostics().error(element, "@McpTool is only allowed on methods");
                valid = false;
                continue;
            }
            if (!(method.getEnclosingElement() instanceof TypeElement)) {
                ctx.diagnostics()
                        .error(method, "@McpTool method %s() must be declared by a type", method.getSimpleName());
                valid = false;
                continue;
            }
            toolMethods.add(method);
        }

        List<McpToolModel> models = new ArrayList<>();
        for (Map.Entry<TypeElement, List<ExecutableElement>> entry :
                groupByDeclaringType(toolMethods).entrySet()) {
            if (!validator.validateDeclaringType(entry.getKey(), entry.getValue())) {
                valid = false;
                continue;
            }
            for (ExecutableElement method : entry.getValue()) {
                Optional<McpToolModel> model = AnnotationMirrors.findByFqn(method, MCP_TOOL)
                        .flatMap(mirror -> validator.validate(entry.getKey(), method, mirror));
                if (model.isEmpty()) {
                    valid = false;
                    continue;
                }
                models.add(model.get());
            }
        }

        if (!validator.validateUniqueNames(models)) {
            valid = false;
        }
        if (!valid || models.isEmpty()) {
            return false;
        }

        models.forEach(invokerEmitter::emit);
        moduleEmitter.emit(models);
        return false;
    }

    /**
     * Groups tool methods by declaring type in a deterministic order: declaring types sorted by
     * qualified name, and each type's tool methods sorted by method name.
     *
     * @param toolMethods every tool method reported by the round
     * @return the ordered grouping
     */
    private Map<TypeElement, List<ExecutableElement>> groupByDeclaringType(List<ExecutableElement> toolMethods) {
        Map<TypeElement, List<ExecutableElement>> byDeclaringType = new LinkedHashMap<>();
        toolMethods.stream()
                .sorted(Comparator.comparing((ExecutableElement method) ->
                                declaringType(method).getQualifiedName().toString())
                        .thenComparing(method -> method.getSimpleName().toString()))
                .forEach(method -> byDeclaringType
                        .computeIfAbsent(declaringType(method), type -> new ArrayList<>())
                        .add(method));
        return byDeclaringType;
    }

    private static TypeElement declaringType(ExecutableElement method) {
        return (TypeElement) method.getEnclosingElement();
    }
}
