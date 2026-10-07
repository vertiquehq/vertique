// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects one {@link AccessPolicy} for a supported framework type or method.
 *
 * <p>A method policy replaces the entire type policy. Distinct policy references in the same
 * complete method set or the same complete type set are rejected. The policy interface carries the
 * direct security requirements; this annotation does not repeat them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresPolicy {

    /** Policy interface whose direct annotations are the requirements. */
    Class<? extends AccessPolicy> value();
}
