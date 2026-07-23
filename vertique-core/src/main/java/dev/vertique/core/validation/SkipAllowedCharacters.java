// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts out of object-level {@code @AllowedCharacters} validation for the annotated field,
 * record component, parameter, or type.
 *
 * <p>When an enclosing type or route is annotated with {@code @AllowedCharacters}, all string
 * fields and parameters are subject to character policy validation. Placing
 * {@code @SkipAllowedCharacters} on an individual field or parameter exempts that element from
 * the inherited policy.
 *
 * <p>When placed on a type, character validation is suppressed for all string fields and parameters
 * of that type regardless of enclosing type or route declarations.
 */
@Documented
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipAllowedCharacters {}
