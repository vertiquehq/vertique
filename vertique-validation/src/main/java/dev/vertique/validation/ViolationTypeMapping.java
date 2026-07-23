// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import java.lang.annotation.Annotation;

/**
 * Binds a constraint annotation type to a violation type string.
 *
 * <p>Contribute mappings via Dagger {@code @IntoSet} multibinding:
 * <pre>{@code
 * @Provides @IntoSet
 * static ViolationTypeMapping myMapping() {
 *     return new ViolationTypeMapping(UniqueEmail.class, "unique_email");
 * }
 * }</pre>
 *
 * @param annotationType the constraint annotation class
 * @param type           the violation type string (e.g., {@code "required"}, {@code "size"})
 */
public record ViolationTypeMapping(Class<? extends Annotation> annotationType, String type) {}
