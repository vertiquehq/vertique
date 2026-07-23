// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import dagger.Module;
import dagger.multibindings.Multibinds;
import java.util.Set;

/**
 * Dagger module that declares the {@link ObjectMapperCustomizer} multibinding set.
 *
 * <p>Included automatically by {@code RestCoreModule}. Non-REST applications
 * can include this module directly in their Dagger component.
 *
 * <p>Individual modules contribute customizers via
 * {@code @Provides @IntoSet ObjectMapperCustomizer}.
 *
 * @see JacksonConfigurer
 */
@Module
public abstract class JsonModule {

    /**
     * Declares the empty-by-default multibinding set of ObjectMapper customizers.
     *
     * @return the set of customizers (may be empty)
     */
    @Multibinds
    abstract Set<ObjectMapperCustomizer> objectMapperCustomizers();
}
