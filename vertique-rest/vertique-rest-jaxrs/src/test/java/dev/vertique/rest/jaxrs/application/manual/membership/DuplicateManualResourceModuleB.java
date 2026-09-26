// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 13's second of two separate manual modules for {@link DuplicateManualResource}; see
 * {@link DuplicateManualResourceModuleA}.
 */
@Module
public final class DuplicateManualResourceModuleB {

    /**
     * Contributes a Dagger-constructed {@link DuplicateManualResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding — the second of two manual matches.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object duplicateManualResourceB(DuplicateManualResource r) {
        return r;
    }
}
