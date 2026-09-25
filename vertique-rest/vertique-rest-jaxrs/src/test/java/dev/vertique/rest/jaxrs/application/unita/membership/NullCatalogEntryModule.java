// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;

/**
 * G-07 (b)'s hand-written catalog entry — deliberately <strong>not</strong> C-GEN-shaped (contrast
 * {@link Case22HandWrittenEntryModule}'s provider, which returns an unrelated instance): a bare
 * {@code GeneratedJaxRsResourceEntry.of(Case22Resource.class, true, () -> null)}, a hand-written
 * entry whose provider fails to construct an instance.
 */
@Module
public final class NullCatalogEntryModule {

    /**
     * Catalogs {@link Case22Resource} (reused purely for its type and path), but the provider
     * always returns {@code null}.
     *
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry nullCatalogEntry() {
        return GeneratedJaxRsResourceEntry.of(Case22Resource.class, true, NullCatalogEntryModule::alwaysNull);
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
