// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * SPI for extracting constraint arguments from a {@link ConstraintViolation}.
 *
 * <p>Custom inspectors can extract domain-specific arguments from constraint annotations.
 * Contribute via Dagger {@code Set<ViolationArgsInspector>} multibinding.
 *
 * <p>The first inspector whose {@link #supports(Class)} returns {@code true} is used.
 * If no custom inspector matches, the built-in inspector extracts well-defined args
 * for standard Jakarta and Hibernate Validator constraints. Unknown constraint annotations
 * receive {@code null} args — provide a custom inspector to add args for custom constraints.
 */
public interface ViolationArgsInspector {

    /**
     * Returns {@code true} if this inspector handles the given constraint annotation type.
     *
     * @param constraintAnnotation the constraint annotation class
     * @return {@code true} if this inspector should extract args for this annotation
     */
    boolean supports(Class<? extends Annotation> constraintAnnotation);

    /**
     * Extracts constraint arguments from the violation.
     *
     * @param violation the constraint violation
     * @return a map of argument names to values, or {@code null} if no meaningful arguments
     */
    @Nullable
    Map<String, Object> extract(ConstraintViolation<?> violation);
}
