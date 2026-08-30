// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor that generates the MCP tool registry from {@code @McpTool}-annotated methods.
 *
 * <p>The single entry point is {@link dev.vertique.codegen.mcp.McpToolProcessor}. For every valid
 * tool method it emits a package-private {@code <DeclaringType>_<method>_McpToolInvoker} carrying a
 * typed {@code Input} record and an immutable {@code McpToolDescriptor}, plus one explicit
 * {@code GeneratedMcpToolsModule} Dagger module that multibinds every invoker. Invocation is direct:
 * no runtime scanning and no reflective fallback exist.
 */
package dev.vertique.codegen.mcp;
