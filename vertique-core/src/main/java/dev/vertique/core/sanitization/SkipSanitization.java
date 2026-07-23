// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts out of inherited sanitization for the annotated element.
 *
 * <p>When placed on a field or record component, sanitization is skipped for that element even
 * if the enclosing type, resource method, or resource class declares {@link Sanitize}.
 * When placed on a type, sanitization is suppressed for all fields and parameters of that type
 * regardless of route-level declarations.
 *
 * <p>This annotation is mutually exclusive with {@link Sanitize} on the same element — placing
 * both on the same target is a configuration error and the behavior is undefined.
 *
 * @see Sanitize
 */
@Documented
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipSanitization {}
