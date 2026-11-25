// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;

/**
 * Hand-written catalog entry — deliberately <strong>not</strong> generated-module-shaped (contrast
 * {@link HandWrittenCatalogEntryModule}'s provider, which returns an unrelated instance): a bare
 * {@code GeneratedJaxRsResourceEntry.of(HandWrittenEntryResource.class, true, () -> null)}, a hand-written
 * entry whose provider fails to construct an instance.
 */
@Module
public final class NullCatalogEntryModule {

    /**
     * Catalogs {@link HandWrittenEntryResource} (reused purely for its type and path), but the provider
     * always returns {@code null}.
     *
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry nullCatalogEntry() {
        return GeneratedJaxRsResourceEntry.of(HandWrittenEntryResource.class, true, NullCatalogEntryModule::alwaysNull);
    }

    /**
     * Always returns {@code null}, standing in for a hand-written provider that fails to construct
     * an instance.
     *
     * @return {@code null}, always
     */
    private static Object alwaysNull() {
        return null;
    }
}
