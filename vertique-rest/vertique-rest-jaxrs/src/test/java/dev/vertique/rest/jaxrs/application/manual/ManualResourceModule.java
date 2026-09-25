// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Hand-written module contributing {@link BlobLikeResource} one instance at a time via
 * {@code @IntoSet} — the manual shape sibling framework modules use, never a generated module.
 */
@Module
public final class ManualResourceModule {

    /**
     * Contributes the Dagger-constructed {@link BlobLikeResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object blobLikeResource(BlobLikeResource r) {
        return r;
    }
}
