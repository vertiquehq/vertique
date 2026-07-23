// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts out of inherited canonicalization for the annotated element.
 *
 * <p>When placed on a field or record component, canonicalization is skipped for that element even
 * if the enclosing type, resource method, or resource class declares {@link Canonicalize}.
 * When placed on a type, canonicalization is suppressed for all fields and parameters of that type
 * regardless of route-level declarations.
 *
 * <p>This annotation is mutually exclusive with {@link Canonicalize} on the same element — placing
 * both on the same target is a configuration error and the behavior is undefined.
 *
 * @see Canonicalize
 */
@Documented
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipCanonicalization {}
