// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Describes a single constraint violation with its property path, message,
 * classified type, and constraint arguments.
 *
 * <p>This is an HTTP-agnostic violation detail. For REST-specific details with location
 * context (query, header, body, etc.), see {@code ValidationErrorDetail} in the {@code rest-core} module.
 *
 * <p>The invalid value is intentionally excluded to prevent accidental leakage of sensitive
 * data (passwords, tokens, PII) in error responses or logs.
 *
 * @param path    the property path of the violation (e.g., {@code "name"}, {@code "address.city"})
 * @param message the interpolated constraint violation message
 * @param type    the classified violation type (e.g., {@code "required"}, {@code "size"}, {@code "min"});
 *                derived from the constraint annotation with annotation simple name as fallback;
 *                {@code null} when type resolution is not configured
 * @param args    constraint arguments extracted from the annotation (e.g., {@code {min: 1, max: 100}}
 *                for {@code @Size}); {@code null} when the constraint has no meaningful arguments
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationDetail(
        String path,
        String message,
        @Nullable String type,
        @Nullable Map<String, Object> args) {

    /**
     * Creates a {@link ViolationDetail} with path and message only, leaving type and args {@code null}.
     *
     * @param path    the property path
     * @param message the violation message
     * @return a new detail with no type or args
     */
    public static ViolationDetail of(String path, String message) {
        return new ViolationDetail(path, message, null, null);
    }
}
