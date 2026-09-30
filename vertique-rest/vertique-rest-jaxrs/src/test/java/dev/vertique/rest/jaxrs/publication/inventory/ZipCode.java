// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed test constraint: {@code @NotNull} plus a five-digit {@code @Pattern}. It is a constraint
 * (meta-annotated {@code @Constraint}) but not itself one of the null-rejecting constraint types, so
 * the inventory cannot tell from its type alone whether an absent value is rejected.
 */
@Constraint(validatedBy = {})
@NotNull
@Pattern(regexp = "[0-9]{5}")
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ZipCode {

    /**
     * The violation message.
     *
     * @return the message
     */
    String message() default "must be a five-digit zip code";

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
}
