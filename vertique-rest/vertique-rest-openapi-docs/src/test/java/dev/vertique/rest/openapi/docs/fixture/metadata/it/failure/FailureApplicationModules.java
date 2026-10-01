// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register one declared application of the metadata failure integration
 * tests and contribute its one resource. Every registration is built exactly as the annotation
 * processor would emit it, since the processor does not run on framework test sources.
 */
public final class FailureApplicationModules {

    private FailureApplicationModules() {}

    /** The application {@code search}, declared by {@link SearchApi}. */
    @Module
    public static final class Search {

        private Search() {}

        /**
         * Registers {@link SearchApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    SearchApi.class, SearchApi.NAME, SearchApi.PATH, List.of(SearchResource.class), false, "", true);
        }

        /**
         * Contributes {@link SearchResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new SearchResource();
        }
    }

    /** The application {@code examples}, declared by {@link ExamplesApi}. */
    @Module
    public static final class Examples {

        private Examples() {}

        /**
         * Registers {@link ExamplesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    ExamplesApi.class,
                    ExamplesApi.NAME,
                    ExamplesApi.PATH,
                    List.of(ExamplesResource.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes {@link ExamplesResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ExamplesResource();
        }
    }
}
