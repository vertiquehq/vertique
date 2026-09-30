// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import java.util.List;
import java.util.Set;

/**
 * The resource instances a row contributes as manual {@code @JaxRsResources}, bound into its
 * component with {@code @BindsInstance} so every row counts its own requests.
 *
 * @param resources the resource instances
 */
public record ContributedResources(List<Object> resources) {

    /**
     * Copies the resource list.
     *
     * @param resources the resource instances
     */
    public ContributedResources {
        resources = List.copyOf(resources);
    }

    /**
     * Returns the given resource instances.
     *
     * @param resources the resource instances
     * @return the contributed resources
     */
    public static ContributedResources of(Object... resources) {
        return new ContributedResources(List.of(resources));
    }

    /** Contributes the bound {@link ContributedResources} into the {@code @JaxRsResources Set<Object>}. */
    @Module
    public static final class Contribution {

        private Contribution() {}

        /**
         * Contributes the row's resource instances.
         *
         * @param contributed the bound resources
         * @return the resource instances
         */
        @Provides
        @ElementsIntoSet
        @JaxRsResources
        static Set<Object> contributedResources(ContributedResources contributed) {
            return Set.copyOf(contributed.resources());
        }
    }
}
