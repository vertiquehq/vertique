// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import dev.vertique.core.exception.ValidationException;
import java.util.List;

/**
 * Thrown when Jakarta Bean Validation constraints are violated.
 *
 * <p>Carries a structured list of {@link ViolationDetail} records describing each
 * individual constraint violation. Use {@link #violations()} to access the details.
 *
 * <p>This exception extends {@link ValidationException} and maps to HTTP 400 by default
 * when caught by the REST error pipeline.
 */
public class BeanValidationException extends ValidationException {

    private final List<ViolationDetail> violations;

    /**
     * Constructs a new exception with the given message and violation details.
     *
     * @param message    the summary message
     * @param violations the list of individual constraint violations; defensively copied
     */
    public BeanValidationException(String message, List<ViolationDetail> violations) {
        this(message, violations, null);
    }

    /**
     * Constructs a new exception with the given message, violation details, and cause.
     *
     * @param message    the summary message
     * @param violations the list of individual constraint violations; defensively copied
     * @param cause      the underlying cause
     */
    public BeanValidationException(String message, List<ViolationDetail> violations, Throwable cause) {
        super(message, cause);
        this.violations = violations != null ? List.copyOf(violations) : List.of();
    }

    /**
     * Returns the list of structured constraint violations.
     *
     * @return an unmodifiable list of {@link ViolationDetail} records
     */
    public List<ViolationDetail> violations() {
        return violations;
    }
}
