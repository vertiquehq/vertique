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
 * TP-006's registration and sole manual resource binding for {@link ViewAppD}: unconditionally
 * inactive, carrying its own {@code openapiPath} {@code "d.yaml"}.
 */
@Module
public final class ViewModuleD {

    private ViewModuleD() {}

    /**
     * Registers {@link ViewAppD}, always inactive.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration viewDRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ViewAppD.class, "d", "/api/d", List.of(ViewResourceD.class), false, "d.yaml", false);
    }

    /**
     * Contributes the Dagger-constructed {@link ViewResourceD} into the {@code @JaxRsResources
     * Set<Object>} multibinding. Never resolved: {@code d} is always inactive, so it is never a
     * membership candidate.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object viewDResource(ViewResourceD r) {
        return r;
    }
}
