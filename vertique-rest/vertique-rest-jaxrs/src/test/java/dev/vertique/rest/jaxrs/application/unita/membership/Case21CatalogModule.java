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
 * Hand-written module in the exact C-GEN shape cataloging {@link Case21Resource} (TP-005 case 21):
 * the {@code Provider<R>} parameter stays exact, even though the actual binding is substituted by
 * {@link Case21SubstitutionModule}.
 */
@Module
public final class Case21CatalogModule {

    /**
     * Catalogs {@link Case21Resource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditional)
     * @param provider lazily constructs the {@link Case21Resource} binding — substituted with a
     *                 {@link Case21SubclassResource} instance by {@link Case21SubstitutionModule}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry case21ResourceEntry(
            @VertxConfig JsonObject config, Provider<Case21Resource> provider) {
        return GeneratedJaxRsResourceEntry.of(Case21Resource.class, true, provider);
    }
}
