// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import java.util.List;
import java.util.Set;

/**
 * The resource instances one composition contributes as manual {@code @JaxRsResources}; a component
 * receives it as a bound instance and {@link Contribution} adds its elements.
 *
 * @param resources the resource instances, each of a distinct class
 */
public record CaseResources(List<Object> resources) {

    /**
     * Copies the instances.
     *
     * @param resources the resource instances
     */
    public CaseResources {
        resources = List.copyOf(resources);
    }

    /**
     * Returns a composition's resources.
     *
     * @param resources the resource instances
     * @return the resources
     */
    public static CaseResources of(Object... resources) {
        return new CaseResources(List.of(resources));
    }

    /** Contributes the bound {@link CaseResources} into the {@code @JaxRsResources Set<Object>}. */
    @Module
    public static final class Contribution {

        private Contribution() {}

        /**
         * Contributes every bound resource instance.
         *
         * @param caseResources the component's bound resources
         * @return the instances
         */
        @Provides
        @ElementsIntoSet
        @JaxRsResources
        static Set<Object> caseResources(CaseResources caseResources) {
            return Set.copyOf(caseResources.resources());
        }
    }
}
