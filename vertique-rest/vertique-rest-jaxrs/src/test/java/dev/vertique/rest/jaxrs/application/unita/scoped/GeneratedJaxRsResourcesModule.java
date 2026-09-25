// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.scoped;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import java.util.Set;

/**
 * Hand-written module in the exact C-GEN shape for the single-proof (TP-003) compilation unit
 * {@code unita.scoped}: a resources-only unit contributing only {@link ScopedResource}, kept apart
 * from {@code unita}'s own module so that {@link ScopedResource}'s catalog entry is included only
 * in TP-003's component (C-GEN's package-resolution rule keeps this module's name and package
 * distinct from {@code unita}'s).
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    /**
     * Unconditionally contributes {@link ScopedResource} to {@code @JaxRsResources} in
     * zero-declaration mode only.
     *
     * @param config       the application configuration (unused; present for parameter-shape
     *                     parity with the conditional bindings in sibling C-GEN modules)
     * @param applications the generated application registration set; a non-empty set means
     *                     explicit mode, so this binding contributes nothing
     * @param provider     lazily constructs {@link ScopedResource}
     * @return a singleton set holding the constructed resource in zero-declaration mode, otherwise
     *     an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> scopedResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<ScopedResource> provider) {
        return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
    }

    /**
     * Catalogs {@link ScopedResource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; {@link ScopedResource} is
     *                 unconditional)
     * @param provider lazily constructs {@link ScopedResource}; scoped {@code @Singleton} by the
     *                 resource class itself, so the Dagger-generated provider caches the instance
     *                 per component
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry scopedResourceEntry(
            @VertxConfig JsonObject config, Provider<ScopedResource> provider) {
        return GeneratedJaxRsResourceEntry.of(ScopedResource.class, true, provider);
    }
}
