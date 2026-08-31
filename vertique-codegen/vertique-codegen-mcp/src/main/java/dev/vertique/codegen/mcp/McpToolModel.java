// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.mcp.tool.McpAccessMode;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * One fully validated {@code @McpTool} declaration, ready to emit.
 *
 * <p>A model exists only for a tool the processor accepted: the declaring type is Dagger-managed,
 * the method is directly invocable, every parameter and the result have an honest type contract, and
 * the effective access mode and JSON profile are resolved. Emission therefore never re-decides
 * anything — {@link McpToolInvokerEmitter} and {@link McpToolsModuleEmitter} read this model and
 * write source.
 *
 * @param declaringType   the Dagger-managed type declaring the tool method
 * @param method          the annotated tool method
 * @param toolName        the protocol tool name from {@code @McpTool(name = …)}
 * @param title           the optional protocol title; {@code null} when blank and omitted from the
 *                        wire
 * @param description     the non-blank protocol description
 * @param requiredClientCapabilities the required top-level client capability names
 * @param readOnlyHint    the {@code readOnlyHint} tool behavior annotation
 * @param destructiveHint the {@code destructiveHint} tool behavior annotation
 * @param idempotentHint  the {@code idempotentHint} tool behavior annotation
 * @param openWorldHint   the {@code openWorldHint} tool behavior annotation
 * @param accessMode      the derived protocol access mode
 * @param roles           the roles a {@code RESTRICTED} tool requires; empty otherwise
 * @param action          the canonical {@code @RequiresAction} value; {@code null} when no action
 *                        was resolved
 * @param jsonProfile     the effective {@code @JsonProfile} id resolved method-over-type;
 *                        {@code null} when the tool declares none and composition selects the
 *                        boundary/global default
 * @param parameters      the declared parameters in source order, including any framework-supplied
 *                        cancellation signal
 * @param returnModel     the validated result contract
 */
record McpToolModel(
        TypeElement declaringType,
        ExecutableElement method,
        String toolName,
        String title,
        String description,
        List<String> requiredClientCapabilities,
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint,
        McpAccessMode accessMode,
        List<String> roles,
        String action,
        String jsonProfile,
        List<McpToolParameterModel> parameters,
        McpToolReturnModel returnModel) {

    /** The suffix every generated invoker's simple name carries. */
    static final String INVOKER_SUFFIX = "_McpToolInvoker";

    /**
     * Canonicalizes the model: collections are defensively copied so an emitted tool cannot be
     * mutated after validation.
     */
    McpToolModel {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(returnModel, "returnModel");
        roles = List.copyOf(roles);
        requiredClientCapabilities = List.copyOf(requiredClientCapabilities);
        parameters = List.copyOf(parameters);
    }

    /**
     * Returns the simple name of this tool's generated invoker,
     * {@code <DeclaringType>_<method>_McpToolInvoker}.
     *
     * @return the frozen generated invoker simple name
     */
    String invokerSimpleName() {
        return declaringType.getSimpleName() + "_" + method.getSimpleName() + INVOKER_SUFFIX;
    }

    /**
     * Returns only the parameters that are members of the input schema — every declared parameter
     * except a framework-supplied cancellation signal.
     *
     * @return the schema members in source order
     */
    List<McpToolParameterModel> schemaParameters() {
        return parameters.stream().filter(McpToolParameterModel::schemaMember).toList();
    }
}
