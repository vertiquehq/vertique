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
 * Hand-written module in the exact C-GEN shape cataloging {@link AmbiguousResource} (TP-005 case
 * 6), always enabled. Paired with {@code manual.membership.AmbiguousResourceManualModule}, which
 * contributes a manual instance of the same class, so a listed
 * {@link AmbiguousResource} has both a catalog match and a manual match.
 */
@Module
public final class AmbiguousResourceCatalogModule {

    /**
     * Catalogs {@link AmbiguousResource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditional)
     * @param provider lazily constructs {@link AmbiguousResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry ambiguousResourceEntry(
            @VertxConfig JsonObject config, Provider<AmbiguousResource> provider) {
        return GeneratedJaxRsResourceEntry.of(AmbiguousResource.class, true, provider);
    }
}
