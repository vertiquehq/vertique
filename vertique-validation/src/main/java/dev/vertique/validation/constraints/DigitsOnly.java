// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string contains only ASCII digit characters {@code 0–9}.
 *
 * <p>This is a composed constraint that delegates to {@link Pattern @Pattern(regexp = "[0-9]+")}
 * and requires the value to be non-empty. For nullable fields, use alongside {@code @NotNull}
 * only when a value is present; null values pass this constraint.
 *
 * <p>Example:
 * <pre>{@code
 * @DigitsOnly
 * private String postalCode;
 * }</pre>
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Pattern(regexp = "[0-9]+")
@Constraint(validatedBy = {})
public @interface DigitsOnly {

    /**
     * The violation message when the constraint fails.
     *
     * @return the message template
     */
    String message() default "must contain only digits";

    /**
     * The validation groups this constraint belongs to.
     *
     * @return the groups array
     */
    Class<?>[] groups() default {};

    /**
     * The payload for this constraint.
     *
     * @return the payload array
     */
    Class<? extends Payload>[] payload() default {};
}
