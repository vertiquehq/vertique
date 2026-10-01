// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import jakarta.inject.Provider;

/**
 * The resource catalogs a {@link CatalogApi} composition can be given, each written in the shape the
 * annotation processor emits: one enabled {@link GeneratedJaxRsResourceEntry} per resource. The sole
 * discovery application selects every enabled entry, so the catalog alone decides what its mount
 * serves. A component lists exactly one of these modules.
 */
public final class CatalogEntries {

    private CatalogEntries() {}

    /**
     * The mixed catalog: the open {@link CatalogResource} beside the three resources that each
     * restrict callers in a different way ({@link AdminResource}, {@link ScopelessResource}, and
     * {@link ActionResource}).
     */
    @Module
    public static final class Mixed {

        private Mixed() {}

        /**
         * Catalogs {@link CatalogResource}, enabled.
         *
         * @param provider constructs the resource
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry catalogResourceEntry(Provider<CatalogResource> provider) {
            return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
        }

        /**
         * Catalogs {@link AdminResource}, enabled.
         *
         * @param provider constructs the resource
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry adminResourceEntry(Provider<AdminResource> provider) {
            return GeneratedJaxRsResourceEntry.of(AdminResource.class, true, provider);
        }

        /**
         * Catalogs {@link ScopelessResource}, enabled.
         *
         * @param provider constructs the resource
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry scopelessResourceEntry(Provider<ScopelessResource> provider) {
            return GeneratedJaxRsResourceEntry.of(ScopelessResource.class, true, provider);
        }

        /**
         * Catalogs {@link ActionResource}, enabled.
         *
         * @param provider constructs the resource
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry actionResourceEntry(Provider<ActionResource> provider) {
            return GeneratedJaxRsResourceEntry.of(ActionResource.class, true, provider);
        }
    }

    /** The open catalog: {@link CatalogResource} alone, so no operation restricts callers. */
    @Module
    public static final class OpenOnly {

        private OpenOnly() {}

        /**
         * Catalogs {@link CatalogResource}, enabled.
         *
         * @param provider constructs the resource
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry catalogResourceEntry(Provider<CatalogResource> provider) {
            return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
        }
    }
}
