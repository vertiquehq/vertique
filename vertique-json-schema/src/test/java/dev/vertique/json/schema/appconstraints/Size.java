// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.appconstraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An application-defined constraint named {@code Size}, deliberately colliding with {@code
 * jakarta.validation.constraints.Size}'s simple name — in a different package, with a different
 * fully-qualified class name — composing {@code @Pattern} instead of a size bound (W3).
 *
 * <p>Declares its own {@code min}/{@code max} attributes, shaped like the real {@code @Size}'s, so
 * that matching this annotation by simple name alone (as the metadata renderer did before W3) risks
 * misreading these differently-typed attributes as if they were {@code jakarta.validation.constraints
 * .Size}'s own — {@code min}/{@code max} here are {@link String}, not {@code int}, so a simple-name
 * match that tries {@code (Integer) attributes.get("max")} fails outright instead of silently
 * misbehaving. Matching by fully-qualified class name must never let this annotation reach that case.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Pattern(regexp = "[A-Z]+")
public @interface Size {

    String message() default "invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String min() default "unbounded";

    String max() default "unbounded";
}
