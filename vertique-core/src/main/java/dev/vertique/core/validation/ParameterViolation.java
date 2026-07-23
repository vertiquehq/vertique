// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

/**
 * Describes a constraint violation on a specific method parameter.
 *
 * <p>Carries the parameter index (zero-based position in the method signature) alongside
 * the violation detail. The parameter index enables callers (such as the REST framework)
 * to map violations to HTTP locations (body, query, header, etc.).
 *
 * @param parameterIndex zero-based index of the violated parameter in the method signature;
 *                       {@code -1} if the parameter index could not be determined
 * @param detail         the violation detail with path, message, type, and args
 */
public record ParameterViolation(int parameterIndex, ViolationDetail detail) {}
