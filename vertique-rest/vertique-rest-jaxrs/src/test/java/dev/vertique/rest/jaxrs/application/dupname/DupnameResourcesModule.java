// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * T023 L26 restoration: manually contributes {@link UnitOneResource} and {@link UnitTwoResource}
 * into {@code @JaxRsResources Set<Object>} — the manual shape
 * {@link dev.vertique.rest.jaxrs.application.manual.ManualResourceModule} uses — for {@code
 * DuplicateNameComponents.RenamedControlComponent}, TP-008's control, whose renamed registrations
 * must compose successfully. Never included in {@code DuplicateNameComponents.DuplicateNameComponent}:
 * every row there fails on the duplicate-name check before any resource resolves, so a missing
 * binding there is harmless and out of this row's scope.
 */
@Module
public final class DupnameResourcesModule {

    private DupnameResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link UnitOneResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object unitOneResource(UnitOneResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link UnitTwoResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object unitTwoResource(UnitTwoResource r) {
        return r;
    }
}
