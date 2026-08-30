// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.support.Identifiers;
import java.beans.Introspector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;

/**
 * Emits the single explicit {@code GeneratedMcpToolsModule} that multibinds every generated invoker
 * and its descriptor.
 *
 * <p>This is the whole tool registry: the application installs the module in its Dagger component
 * and the server injects the resulting sets. Nothing is discovered at runtime, so a tool that is not
 * bound here does not exist.
 *
 * <pre>{@code
 * @Generated("dev.vertique.codegen.mcp.McpToolProcessor")
 * @Module
 * public abstract class GeneratedMcpToolsModule {
 *
 *     @Provides
 *     @IntoSet
 *     static McpToolInvoker weatherTools_lookup_invoker(WeatherTools_lookup_McpToolInvoker invoker) {
 *         return invoker;
 *     }
 *
 *     @Provides
 *     @IntoSet
 *     static McpToolDescriptor weatherTools_lookup_descriptor(WeatherTools_lookup_McpToolInvoker invoker) {
 *         return invoker.descriptor();
 *     }
 * }
 * }</pre>
 *
 * <p>The descriptor binding is derived from the invoker instance rather than rebuilt, so the
 * published descriptor set and the dispatchable invoker set cannot drift.
 *
 * <p><strong>Package resolution.</strong> The module is emitted into the longest common package
 * prefix of every tool's declaring type, overridden by {@code -Avertique.codegen.package}. Because
 * generated invokers are package-private, every tool must resolve to that same package; a tool
 * outside it is a compile error naming the option that fixes it, rather than a module that does not
 * compile.
 */
final class McpToolsModuleEmitter {

    /** The frozen simple name of the generated module. */
    static final String MODULE_SIMPLE_NAME = "GeneratedMcpToolsModule";

    /** The package used when no tool declares one — only reachable from the unnamed package. */
    private static final String FALLBACK_PACKAGE = "vertique.generated.mcp";

    private static final ClassName DAGGER_MODULE = ClassName.get("dagger", "Module");
    private static final ClassName DAGGER_PROVIDES = ClassName.get("dagger", "Provides");
    private static final ClassName DAGGER_INTO_SET = ClassName.get("dagger.multibindings", "IntoSet");
    private static final ClassName MCP_TOOL_INVOKER = ClassName.get("dev.vertique.mcp.tool", "McpToolInvoker");
    private static final ClassName MCP_TOOL_DESCRIPTOR = ClassName.get("dev.vertique.mcp.tool", "McpToolDescriptor");

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpToolsModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the module for every validated tool, ordered by tool name so the generated source is
     * byte-identical across builds of the same inputs.
     *
     * @param models the validated tool models; must not be empty
     */
    void emit(List<McpToolModel> models) {
        if (models.isEmpty()) {
            return;
        }
        String packageName = resolvePackage(models);

        List<McpToolModel> ordered = new ArrayList<>(models);
        ordered.sort(Comparator.comparing(McpToolModel::toolName));

        TypeSpec.Builder module = TypeSpec.classBuilder(MODULE_SIMPLE_NAME)
                .addJavadoc(
                        "The generated MCP tool registry: every {@code @McpTool} in this compilation, multibound as\n"
                                + "an invoker and a descriptor. Install this module in the application component.\n")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", McpToolProcessor.PROCESSOR_FQN)
                        .build())
                .addAnnotation(DAGGER_MODULE);

        Set<String> usedMethodNames = new LinkedHashSet<>();
        for (McpToolModel model : ordered) {
            String toolPackage = ctx.packageNameOf(model.declaringType());
            if (!toolPackage.equals(packageName)) {
                ctx.diagnostics()
                        .error(
                                model.declaringType(),
                                "@McpTool %s is declared in package '%s', but %s resolves to package '%s'."
                                        + " A generated invoker is package-private, so every tool must resolve to the"
                                        + " module's package: move the tool or set -A%s=%s.",
                                model.toolName(),
                                toolPackage,
                                MODULE_SIMPLE_NAME,
                                packageName,
                                CodegenContext.OPTION_OUTPUT_PACKAGE,
                                toolPackage);
                return;
            }
            ClassName invokerType = ClassName.get(packageName, model.invokerSimpleName());
            String base = bindingBaseName(model, usedMethodNames);

            module.addMethod(binding(base + "_invoker", MCP_TOOL_INVOKER, invokerType, "return invoker", model))
                    .addMethod(binding(
                            base + "_descriptor",
                            MCP_TOOL_DESCRIPTOR,
                            invokerType,
                            "return invoker.descriptor()",
                            model));
        }

        JavaFile javaFile = JavaFile.builder(packageName, module.build()).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", MODULE_SIMPLE_NAME, e.getMessage());
        }
    }

    // --- Internal helpers ---

    private MethodSpec binding(
            String methodName, ClassName boundType, ClassName invokerType, String body, McpToolModel model) {
        return MethodSpec.methodBuilder(methodName)
                .addJavadoc(
                        "Binds the {@code $L} tool's $L.\n\n@param invoker the generated invoker\n@return the"
                                + " multibound contribution\n",
                        model.toolName(),
                        boundType.simpleName())
                .addModifiers(Modifier.STATIC)
                .addAnnotation(DAGGER_PROVIDES)
                .addAnnotation(DAGGER_INTO_SET)
                .returns(boundType)
                .addParameter(invokerType, "invoker")
                .addStatement(body)
                .build();
    }

    /**
     * Derives a deterministic, collision-free binding method base name from the tool's declaring
     * type and method, disambiguating with the declaring type's package only when two tools would
     * otherwise collide.
     */
    private String bindingBaseName(McpToolModel model, Set<String> used) {
        String base = Identifiers.sanitize(
                Introspector.decapitalize(model.declaringType().getSimpleName().toString()) + "_"
                        + model.method().getSimpleName());
        String candidate = base;
        int ordinal = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + ordinal++;
        }
        return candidate;
    }

    /**
     * Resolves the module's package: the {@code -Avertique.codegen.package} override when set,
     * otherwise the longest common prefix of every tool's declaring package.
     */
    private String resolvePackage(List<McpToolModel> models) {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        Set<String> packages = new TreeSet<>();
        models.forEach(model -> packages.add(ctx.packageNameOf(model.declaringType())));
        String lcp =
                packages.stream().reduce(PackageResolver::longestCommonPrefix).orElse(FALLBACK_PACKAGE);
        return lcp.isBlank() ? FALLBACK_PACKAGE : lcp;
    }
}
