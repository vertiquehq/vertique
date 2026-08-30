// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for repeatable {@link RegisterAs} declarations.
 *
 * <p>The Java compiler creates this container when more than one {@code @RegisterAs} annotation
 * is placed on a type. Application code should normally use repeated {@code @RegisterAs}
 * declarations directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface RegisterAsContainer {

    /**
     * The direct registration declarations contained by this annotation.
     *
     * @return the contained registrations
     */
    RegisterAs[] value();
}
