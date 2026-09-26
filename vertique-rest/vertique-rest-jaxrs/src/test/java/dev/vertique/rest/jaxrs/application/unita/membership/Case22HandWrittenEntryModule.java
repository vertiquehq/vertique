// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;

/**
 * TP-005 case 22's hand-written catalog entry — deliberately <strong>not</strong> C-GEN-shaped
 * (contrast {@link Case21CatalogModule}'s exact {@code Provider<R>} parameter shape): a bare
 * {@code GeneratedJaxRsResourceEntry.of(Case22Resource.class, true, () -> new
 * Case22UnrelatedResource())}, exactly as the contract specifies for a hand-written entry whose
 * provider returns an unrelated type.
 */
@Module
public final class Case22HandWrittenEntryModule {

    /**
     * Catalogs {@link Case22Resource}, but the provider returns a {@link Case22UnrelatedResource}
     * instance — a hand-written entry an application developer, not the processor, might write.
     *
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry case22ResourceEntry() {
        return GeneratedJaxRsResourceEntry.of(Case22Resource.class, true, () -> new Case22UnrelatedResource());
    }
}
