// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * G-06's hand-written module contributing {@link ReentrantResource} one instance at a time via
 * {@code @IntoSet} — the manual shape sibling framework modules use, never a generated module.
 * Included only in its own nested component(s), so its re-entrant dependency never interferes with
 * any other proof.
 */
@Module
public final class ReentrantResourceModule {

    /**
     * Contributes the Dagger-constructed {@link ReentrantResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object reentrantResource(ReentrantResource r) {
        return r;
    }
}
