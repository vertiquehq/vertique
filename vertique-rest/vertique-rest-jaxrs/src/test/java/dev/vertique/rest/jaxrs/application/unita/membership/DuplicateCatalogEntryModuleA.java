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
 * TP-005 case 14's first of two separate catalog-entry modules for
 * {@link DuplicateCatalogResource}; see {@link DuplicateCatalogEntryModuleB}. Two separate
 * {@code @Provides} methods, each contributing its own entry instance, are required: the
 * generated-code contract types keep identity equality, so Dagger's set never collapses the
 * duplicate (R5).
 */
@Module
public final class DuplicateCatalogEntryModuleA {

    /**
     * Catalogs {@link DuplicateCatalogResource} for explicit-mode selection — the first of two
     * entries of the same class.
     *
     * @param config   the application configuration (unused)
     * @param provider lazily constructs {@link DuplicateCatalogResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry duplicateCatalogResourceEntryA(
            @VertxConfig JsonObject config, Provider<DuplicateCatalogResource> provider) {
        return GeneratedJaxRsResourceEntry.of(DuplicateCatalogResource.class, true, provider);
    }
}
