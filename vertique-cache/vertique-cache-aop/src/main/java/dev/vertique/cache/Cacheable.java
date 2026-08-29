// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a method whose successful result may be stored in a named cache region. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect(ordering = 200)
public @interface Cacheable {
    String name();

    /** Ordered selector paths; each names a parameter root plus optional accessor segments. */
    String[] key();

    CacheMode mode() default CacheMode.DEFAULT;

    long ttlSeconds() default -1;

    CacheIdentity identity() default CacheIdentity.EFFECTIVE_PRINCIPAL;

    AnonymousCachePolicy anonymous() default AnonymousCachePolicy.BYPASS;
}
