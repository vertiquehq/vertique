// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Describes a single validation error with context about where and what failed.
 *
 * <p>Designed to work with both OpenAPI validation and Jakarta Bean Validation.
 * When serialized as JSON, {@code null} fields are omitted so clients see only the
 * context that is actually available.
 *
 * <p>Use the {@link #of(String, String)} factory method when location, type, and args context
 * are not applicable.
 *
 * @param path     path identifying the invalid field (e.g., {@code "/name"}, {@code "email"})
 * @param detail   human-readable description of the validation failure
 * @param location where the invalid value was found — {@code "body"}, {@code "query"},
 *                 {@code "header"}, {@code "path"}, {@code "cookie"}, {@code "form"},
 *                 {@code "file"}, or {@code null}
 * @param type     error classification — {@code "required"}, {@code "invalid_value"},
 *                 {@code "format"}, {@code "type"}, or {@code null}
 * @param args     constraint arguments extracted from the annotation (e.g., {@code {min: 1, max: 100}}
 *                 for {@code @Size}); {@code null} when the constraint has no meaningful arguments
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorDetail(
        String path,
        String detail,
        @Nullable String location,
        @Nullable String type,
        @Nullable Map<String, Object> args) {

    /**
     * Creates a {@link ValidationErrorDetail} with path and detail only, leaving location,
     * type, and args {@code null}. Use this for simple validation errors without OpenAPI or
     * parameter-location context.
     *
     * @param path   path identifying the invalid field
     * @param detail human-readable description of the validation failure
     * @return a new {@link ValidationErrorDetail} with {@code null} location, type, and args
     */
    public static ValidationErrorDetail of(String path, String detail) {
        return new ValidationErrorDetail(path, detail, null, null, null);
    }
}
