// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;

/**
 * Hand-written catalog entry — deliberately <strong>not</strong> generated-module-shaped
 * (contrast {@link SubstitutedBindingCatalogModule}'s exact {@code Provider<R>} parameter shape): a bare
 * {@code GeneratedJaxRsResourceEntry.of(HandWrittenEntryResource.class, true, () -> new
 * UnrelatedEntryResource())}, the shape of a hand-written entry whose
 * provider returns an unrelated type.
 */
@Module
public final class HandWrittenCatalogEntryModule {

    /**
     * Catalogs {@link HandWrittenEntryResource}, but the provider returns a {@link UnrelatedEntryResource}
     * instance — a hand-written entry an application developer, not the processor, might write.
     *
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry handWrittenEntryResourceEntry() {
        return GeneratedJaxRsResourceEntry.of(HandWrittenEntryResource.class, true, () -> new UnrelatedEntryResource());
    }
}
