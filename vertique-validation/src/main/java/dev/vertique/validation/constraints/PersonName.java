// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string contains only characters suitable for a personal name.
 *
 * <p>This is a composed constraint that delegates to
 * {@link AllowedCharacters @AllowedCharacters(policy = PersonNamePolicy.class)}.
 * Accepts Unicode letters across all scripts (Latin, CJK, Arabic, Cyrillic, etc.)
 * plus common name punctuation: space, apostrophe, hyphen, period, and comma.
 *
 * <p>Null values pass; pair with {@code @NotBlank} to require a value.
 *
 * <p>Example:
 * <pre>{@code
 * @PersonName
 * private String firstName;
 * }</pre>
 *
 * @see PersonNamePolicy
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@AllowedCharacters(policy = PersonNamePolicy.class)
@Constraint(validatedBy = {})
public @interface PersonName {

    /**
     * The violation message when the constraint fails.
     *
     * @return the message template
     */
    String message() default "contains characters not allowed in a person name";

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
