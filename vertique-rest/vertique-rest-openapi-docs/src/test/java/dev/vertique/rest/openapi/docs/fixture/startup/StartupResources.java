// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Contributes the startup-check resources as manual {@code @JaxRsResources} instances, one nested
 * module per resource.
 */
public final class StartupResources {

    private StartupResources() {}

    /** Contributes {@link DormantResource}. */
    @Module
    public static final class Dormant {

        private Dormant() {}

        /**
         * Contributes the Dagger-constructed {@link DormantResource}.
         *
         * @param resource the injected resource instance
         * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object dormantResource(DormantResource resource) {
            return resource;
        }
    }

    /** Contributes {@link LongNameResource}. */
    @Module
    public static final class LongName {

        private LongName() {}

        /**
         * Contributes the Dagger-constructed {@link LongNameResource}.
         *
         * @param resource the injected resource instance
         * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object longNameResource(LongNameResource resource) {
            return resource;
        }
    }

    /** Contributes {@link OpsResource}. */
    @Module
    public static final class Ops {

        private Ops() {}

        /**
         * Contributes the Dagger-constructed {@link OpsResource}.
         *
         * @param resource the injected resource instance
         * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object opsResource(OpsResource resource) {
            return resource;
        }
    }
}
