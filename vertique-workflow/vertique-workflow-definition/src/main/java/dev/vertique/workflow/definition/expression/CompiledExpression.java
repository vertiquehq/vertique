// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

/**
 * An immutable compiled representation of an expression string, produced by
 * {@link ExpressionProfile#compile(String, ExpressionEnv)}.
 *
 * <p>Instances are created by the expression profile and must be treated as opaque values by all
 * callers outside the profile implementation. The {@link #handle()} field is an opaque
 * profile-internal object; callers MUST NOT downcast it to any third-party EL type. The only valid
 * use of {@code handle} is to pass it back to the same profile via {@link ExpressionProfile#evaluate}
 * or {@link ExpressionProfile#evaluateBoolean}.
 *
 * <p>Construction validates:
 * <ul>
 *   <li>{@code canonicalForm} — non-null and non-blank</li>
 *   <li>{@code resultType} — non-null</li>
 *   <li>{@code handle} — non-null</li>
 * </ul>
 *
 * @param canonicalForm the whitespace-normalised canonical string representation of the expression,
 *                      produced by the profile's unparser (e.g., {@code CelUnparser}); used as
 *                      input to {@link ExpressionFingerprint#of(CompiledExpression)}
 * @param resultType    the compile-time result type of the expression
 * @param handle        opaque profile-implementation handle; callers MUST NOT downcast
 */
public record CompiledExpression(String canonicalForm, ExpressionType resultType, Object handle) {

    /**
     * Compact canonical constructor — validates all components.
     *
     * @param canonicalForm non-null, non-blank canonical expression string
     * @param resultType    non-null result type
     * @param handle        non-null profile handle
     * @throws IllegalArgumentException if any component is null or {@code canonicalForm} is blank
     */
    public CompiledExpression {
        if (canonicalForm == null || canonicalForm.isBlank()) {
            throw new IllegalArgumentException("canonicalForm must be non-null and non-blank");
        }
        if (resultType == null) {
            throw new IllegalArgumentException("resultType must be non-null");
        }
        if (handle == null) {
            throw new IllegalArgumentException("handle must be non-null");
        }
    }
}
