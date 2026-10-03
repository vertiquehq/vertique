// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * TP-006's registration and sole manual resource binding for {@link ViewAppA}: unconditionally
 * active, carrying its own {@code openapiPath} {@code "a.yaml"}.
 */
@Module
public final class ViewModuleA {

    private ViewModuleA() {}

    /**
     * Registers {@link ViewAppA}, always active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration viewARegistration() {
        return GeneratedRestApplicationRegistration.of(
                ViewAppA.class, "a", "/api/a", List.of(ViewResourceA.class), false, "a.yaml", true);
    }

    /**
     * Contributes the Dagger-constructed {@link ViewResourceA} into the {@code @JaxRsResources
     * Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object viewAResource(ViewResourceA r) {
        return r;
    }
}
