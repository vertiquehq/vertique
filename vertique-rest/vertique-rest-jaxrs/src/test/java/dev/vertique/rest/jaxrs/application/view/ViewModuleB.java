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
 * TP-006's registration and sole manual resource binding for {@link ViewAppB}: unconditionally
 * active, carrying its own {@code openapiPath} {@code "b.yaml"} and no configuration entry.
 */
@Module
public final class ViewModuleB {

    private ViewModuleB() {}

    /**
     * Registers {@link ViewAppB}, always active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration viewBRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ViewAppB.class, "b", "/api/b", List.of(ViewResourceB.class), false, "b.yaml", true);
    }

    /**
     * Contributes the Dagger-constructed {@link ViewResourceB} into the {@code @JaxRsResources
     * Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object viewBResource(ViewResourceB r) {
        return r;
    }
}
