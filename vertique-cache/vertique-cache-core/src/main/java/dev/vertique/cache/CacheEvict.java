// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Evicts an exact key or the complete contents of a named cache after success. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CacheEvict.List.class)
@Aspect(ordering = 100)
public @interface CacheEvict {
    String name();

    String key() default "";

    boolean clear() default false;

    /** Container for repeatable eviction declarations. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        CacheEvict[] value();
    }
}
