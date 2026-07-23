// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;

/**
 * SPI for programmatic constraint annotation to violation type mapping.
 *
 * <p>Use when a simple {@link ViolationTypeMapping} annotation to type binding is not sufficient
 * (e.g., mapping by package prefix or annotation metadata). Contribute via Dagger
 * {@code Set<ViolationTypeMapper>} multibinding.
 *
 * <p>Return {@code null} to indicate this mapper does not handle the given annotation,
 * allowing the next mapper or fallback to process it.
 */
public interface ViolationTypeMapper {

    /**
     * Maps a constraint annotation class to a violation type string.
     *
     * @param constraintAnnotation the constraint annotation class
     * @return the violation type string, or {@code null} if not handled
     */
    @Nullable
    String typeFor(Class<? extends Annotation> constraintAnnotation);
}
