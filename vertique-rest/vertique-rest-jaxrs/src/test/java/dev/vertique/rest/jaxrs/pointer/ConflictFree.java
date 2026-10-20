// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.pointer;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint: the annotated type reports itself as conflicting through
 * {@link Conflicting#conflicts()}. Its violations belong to the object itself, not to a property.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ConflictFree.Check.class)
public @interface ConflictFree {

    /**
     * The violation message.
     *
     * @return the message
     */
    String message() default "must not conflict";

    /**
     * The validation groups.
     *
     * @return the groups
     */
    Class<?>[] groups() default {};

    /**
     * The payload.
     *
     * @return the payload
     */
    Class<? extends Payload>[] payload() default {};

    /** A value that can tell whether it conflicts with itself. */
    interface Conflicting {

        /**
         * Whether the value is internally inconsistent.
         *
         * @return {@code true} when the value conflicts
         */
        boolean conflicts();
    }

    /** The validator behind {@link ConflictFree}. */
    class Check implements ConstraintValidator<ConflictFree, Conflicting> {

        @Override
        public boolean isValid(Conflicting value, ConstraintValidatorContext context) {
            return value == null || !value.conflicts();
        }
    }
}
