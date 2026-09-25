// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 13's first of two separate manual modules for {@link DuplicateManualResource}; see
 * {@link DuplicateManualResourceModuleB}.
 */
@Module
public final class DuplicateManualResourceModuleA {

    /**
     * Contributes a Dagger-constructed {@link DuplicateManualResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding — the first of two manual matches.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object duplicateManualResourceA(DuplicateManualResource r) {
        return r;
    }
}
