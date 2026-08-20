// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * One validated parameter of a {@code @McpTool} method.
 *
 * <p>Two names are carried deliberately and never conflated. {@code protocolName} is the wire name
 * a client sends in the {@code tools/call} argument object; it is preserved verbatim and is not
 * constrained to the Java identifier grammar. {@code componentName} is the collision-safe Java
 * identifier the generated {@code Input} carrier record uses for the same value.
 *
 * <p>A {@code McpCancellationSignal} parameter is framework-supplied: it is excluded from the input
 * schema and from the {@code Input} carrier, and the generated invoker passes the signal it received
 * in {@code prepare(...)} straight through at the declared position. Such a parameter is flagged
 * with {@code cancellationSignal} and carries no protocol name or description.
 *
 * @param element            the declared parameter element, used as the diagnostic anchor
 * @param protocolName       the wire name of the argument; empty for a cancellation signal
 * @param componentName      the collision-safe Java identifier used in the generated carrier
 * @param description        the {@code @McpToolParam} description; empty for a cancellation signal
 * @param type               the declared parameter type
 * @param cancellationSignal {@code true} when the parameter is the framework-supplied
 *                           {@code McpCancellationSignal} rather than a schema member
 */
record McpToolParameterModel(
        VariableElement element,
        String protocolName,
        String componentName,
        String description,
        TypeMirror type,
        boolean cancellationSignal) {

    /**
     * Returns {@code true} when this parameter is a member of the tool's input schema — that is,
     * every parameter except the framework-supplied cancellation signal.
     *
     * @return {@code true} when the parameter is materialized from client arguments
     */
    boolean schemaMember() {
        return !cancellationSignal;
    }
}
