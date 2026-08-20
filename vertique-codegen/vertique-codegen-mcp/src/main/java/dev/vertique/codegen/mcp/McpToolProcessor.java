// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that turns {@code @McpTool}-annotated methods into generated MCP tool
 * registry source.
 *
 * <p>The generation contract is: one package-private
 * {@code <DeclaringType>_<method>_McpToolInvoker} per tool — holding a typed {@code Input} carrier
 * record, an immutable {@code McpToolDescriptor} built once at composition, and a direct call to the
 * application method — plus a single {@code GeneratedMcpToolsModule} Dagger module that multibinds
 * every emitted invoker. No runtime scanning and no reflective invocation fallback exist; a tool
 * declaration that cannot be invoked directly is a compile error rather than a degraded binding.
 *
 * <p><strong>Skeleton:</strong> this processor is registered and claims {@code @McpTool}, but does
 * not yet emit any source or report any diagnostic. It exists so the frozen contract matrix in
 * {@code McpToolProcessorCompileTest} compiles and fails on its decisive assertions — missing
 * generated module/invoker and missing targeted diagnostics — rather than on infrastructure. The
 * generation slice replaces this body.
 */
@SupportedAnnotationTypes("dev.vertique.mcp.annotation.McpTool")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class McpToolProcessor extends AbstractProcessor {

    /**
     * Constructs a new {@code McpToolProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public McpToolProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Emits nothing yet; returns {@code false} so other processors continue to see the annotated
     * elements.
     *
     * @param annotations the annotation types being processed
     * @param round       the current round environment
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        return false;
    }
}
