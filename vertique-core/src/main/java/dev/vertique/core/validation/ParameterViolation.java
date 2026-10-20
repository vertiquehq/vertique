// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import jakarta.annotation.Nullable;

/**
 * Describes a constraint violation on a specific method parameter.
 *
 * <p>Carries the parameter index (zero-based position in the method signature) alongside
 * the violation detail. The parameter index enables callers (such as the REST framework)
 * to map violations to HTTP locations (body, query, header, etc.).
 *
 * @param parameterIndex zero-based index of the violated parameter in the method signature;
 *                       {@code -1} if the parameter index could not be determined
 * @param detail         the violation detail; its path is the Java path relative to the parameter
 * @param propertyPath   INTERNAL framework seam: the structured path relative to the parameter value,
 *                       {@link PropertyPath#root()} for a violation of the value itself, or
 *                       {@code null} when the producer supplies none or conversion failed; outside the
 *                       Stable promise like {@link PropertyPath}
 */
public record ParameterViolation(
        int parameterIndex,
        ViolationDetail detail,
        @Nullable PropertyPath propertyPath) {

    /**
     * Creates a violation without a structured path; {@link #propertyPath()} is {@code null}.
     *
     * @param parameterIndex zero-based parameter index; {@code -1} when it could not be determined
     * @param detail         the violation detail
     */
    public ParameterViolation(int parameterIndex, ViolationDetail detail) {
        this(parameterIndex, detail, null);
    }
}
