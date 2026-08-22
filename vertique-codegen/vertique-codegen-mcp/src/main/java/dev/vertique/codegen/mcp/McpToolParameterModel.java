// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import java.util.List;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * One validated parameter of a {@code @McpTool} method.
 *
 * <p>Two names are carried deliberately and never conflated. {@code protocolName} is the wire name
 * a client sends in the {@code tools/call} argument object; it is preserved verbatim (it may be a
 * Java keyword, contain hyphens, or otherwise not be a legal identifier) and is emitted on the
 * generated carrier component as {@code @JsonProperty(protocolName)}. {@code componentName} is the
 * collision-safe generated record component name ({@code argument0}, {@code argument1}, ...),
 * positional and therefore never derived from — and never able to collide on — the protocol name.
 *
 * <p>A {@code McpCancellationSignal} parameter is framework-supplied: it is excluded from the input
 * schema and from the {@code Input} carrier, and the generated invoker passes the signal it received
 * in {@code prepare(...)} straight through at the declared position. Such a parameter is flagged
 * with {@code cancellationSignal} and carries no protocol name, description, or resolved policies.
 *
 * @param element            the declared parameter element, used as the diagnostic anchor
 * @param protocolName       the wire name of the argument; empty for a cancellation signal
 * @param componentName      the collision-safe positional Java identifier used in the generated
 *                           carrier ({@code argument0}, {@code argument1}, ...)
 * @param description        the {@code @McpToolParam} description; empty for a cancellation signal
 * @param type               the declared parameter type
 * @param cancellationSignal {@code true} when the parameter is the framework-supplied
 *                           {@code McpCancellationSignal} rather than a schema member
 * @param canonicalizers     the resolved final REST-effective canonicalizer chain, base (never
 *                           {@code @Skip*}); empty for a cancellation signal
 * @param sanitizers         the resolved final REST-effective sanitizer chain, base (never
 *                           {@code @Skip*}); empty for a cancellation signal
 */
record McpToolParameterModel(
        VariableElement element,
        String protocolName,
        String componentName,
        String description,
        TypeMirror type,
        boolean cancellationSignal,
        List<TypeMirror> canonicalizers,
        List<TypeMirror> sanitizers) {

    /**
     * Canonicalizes the model: the resolved policy chains are defensively copied.
     */
    McpToolParameterModel {
        canonicalizers = List.copyOf(canonicalizers);
        sanitizers = List.copyOf(sanitizers);
    }

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
