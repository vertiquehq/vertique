// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;

/**
 * Hand-written module in the generated-module shape cataloging {@link SubstitutedBindingResource}:
 * the {@code Provider<R>} parameter stays exact, even though the actual binding is substituted by
 * {@link BindingSubstitutionModule}.
 */
@Module
public final class SubstitutedBindingCatalogModule {

    /**
     * Catalogs {@link SubstitutedBindingResource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditional)
     * @param provider lazily constructs the {@link SubstitutedBindingResource} binding — substituted with a
     *                 {@link SubstitutedBindingSubclassResource} instance by {@link BindingSubstitutionModule}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry substitutedBindingResourceEntry(
            @VertxConfig JsonObject config, Provider<SubstitutedBindingResource> provider) {
        return GeneratedJaxRsResourceEntry.of(SubstitutedBindingResource.class, true, provider);
    }
}
