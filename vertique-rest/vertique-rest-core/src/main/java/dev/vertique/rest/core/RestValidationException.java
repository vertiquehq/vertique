// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import dev.vertique.core.exception.ValidationException;
import java.util.List;

/**
 * REST-layer validation exception carrying a structured list of {@link ValidationErrorDetail} records.
 *
 * <p>Extend this exception (or throw it directly) when request validation fails and you want to
 * surface per-field error context in the HTTP 400 response body. The framework bridges Vert.x
 * OpenAPI {@code SchemaValidationException} and {@code ValidatorException} into instances of
 * this class so that the {@link ValidationProblemDetail} response body is automatically populated
 * with field paths, locations, and error types.
 *
 * <p>The {@code errors} list is defensively copied on construction and the returned list is
 * unmodifiable.
 */
public class RestValidationException extends ValidationException {

    private final List<ValidationErrorDetail> errors;

    /**
     * Constructs a new exception with the given message and structured field errors.
     * The provided list is defensively copied.
     *
     * @param message the detail message
     * @param errors  the list of individual validation failures; {@code null} is treated as empty
     */
    public RestValidationException(String message, List<ValidationErrorDetail> errors) {
        super(message);
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    /**
     * Constructs a new exception with the given message, structured field errors, and an underlying cause.
     * The provided list is defensively copied.
     *
     * @param message the detail message
     * @param errors  the list of individual validation failures; {@code null} is treated as empty
     * @param cause   the underlying cause
     */
    public RestValidationException(String message, List<ValidationErrorDetail> errors, Throwable cause) {
        super(message, cause);
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    /**
     * Returns the list of structured validation failures associated with this exception.
     * Returns an empty list if no errors were provided.
     *
     * @return an unmodifiable list of {@link ValidationErrorDetail} records
     */
    public List<ValidationErrorDetail> errors() {
        return errors;
    }
}
