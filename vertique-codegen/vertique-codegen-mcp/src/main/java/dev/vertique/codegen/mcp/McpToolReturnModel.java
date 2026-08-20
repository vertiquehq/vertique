// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import javax.lang.model.type.TypeMirror;

/**
 * The validated result contract of a {@code @McpTool} method.
 *
 * <p>Four handler shapes are supported: {@code T}, {@code Future<T>}, {@code McpToolResult<T>}, and
 * {@code Future<McpToolResult<T>>}. {@code void} — and every raw, wildcard, or unresolved result —
 * is rejected before a model is built, so a {@code McpToolReturnModel} always describes a result
 * that can produce tool content.
 *
 * @param declaredType the method's declared return type, exactly as written
 * @param resultType   the type that carries the tool's value: the {@code T} of any {@code Future}
 *                     and/or {@code McpToolResult} wrapper, or the declared type itself
 * @param shape        which of the four supported handler shapes was declared
 */
record McpToolReturnModel(TypeMirror declaredType, TypeMirror resultType, Shape shape) {

    /** The supported handler result shapes. */
    enum Shape {

        /** A plain value: {@code T}. */
        VALUE,

        /** An asynchronous plain value: {@code Future<T>}. */
        FUTURE_VALUE,

        /** A handler-authored rich result: {@code McpToolResult<T>}. */
        TOOL_RESULT,

        /** An asynchronous handler-authored rich result: {@code Future<McpToolResult<T>>}. */
        FUTURE_TOOL_RESULT
    }

    /**
     * Returns {@code true} when the declared return type is a {@code Future}, so the generated
     * invoker composes on it rather than wrapping a value that is already available.
     *
     * @return {@code true} for {@code Future<T>} and {@code Future<McpToolResult<T>>}
     */
    boolean asynchronous() {
        return shape == Shape.FUTURE_VALUE || shape == Shape.FUTURE_TOOL_RESULT;
    }

    /**
     * Returns {@code true} when the handler itself produces the {@code McpToolResult}, so the
     * generated invoker passes it through instead of building content from a plain value.
     *
     * @return {@code true} for {@code McpToolResult<T>} and {@code Future<McpToolResult<T>>}
     */
    boolean handlerAuthored() {
        return shape == Shape.TOOL_RESULT || shape == Shape.FUTURE_TOOL_RESULT;
    }
}
