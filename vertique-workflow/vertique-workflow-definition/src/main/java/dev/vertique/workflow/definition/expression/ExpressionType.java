// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

/**
 * Classifies the compile-time result type of a {@link CompiledExpression}.
 *
 * <p>Only {@link #BOOLEAN} is accepted by route validation in Slice F; the full set is exposed so
 * the expression profile can evolve to support richer routing logic in future slices.
 */
public enum ExpressionType {

    /** Expression evaluates to a boolean ({@code true}/{@code false}). */
    BOOLEAN,

    /** Expression evaluates to a string value. */
    STRING,

    /** Expression evaluates to a numeric value (integer or floating-point). */
    NUMBER,

    /**
     * Catch-all for expressions whose result type cannot be narrowed to one of the named variants
     * at compile time (e.g., dynamic access on a {@code dyn}-typed value).
     */
    DYN
}
