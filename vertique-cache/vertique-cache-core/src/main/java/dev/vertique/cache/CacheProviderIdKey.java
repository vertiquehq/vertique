// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dagger.MapKey;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provider SPI: the Dagger map key a cache provider module uses to contribute its
 * bounded provider id for one semantic {@link CacheMode}. This annotation and
 * {@link CacheModeKey} are the provider extension point, not internal machinery.
 */
@MapKey
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CacheProviderIdKey {
    CacheMode value();
}
