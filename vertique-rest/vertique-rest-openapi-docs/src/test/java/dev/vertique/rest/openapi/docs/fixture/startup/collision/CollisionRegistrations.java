// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import java.util.List;

/**
 * Hand-written registration modules for the route-collision declarations, each calling
 * {@link GeneratedRestApplicationRegistration#of} exactly as a generated module does. Neither uses
 * discovery membership: each lists its resource, so both can be registered in one component.
 */
public final class CollisionRegistrations {

    private CollisionRegistrations() {}

    /** Registers {@link PublicRootApi}, active, holding {@link CatalogResource}. */
    @Module
    public static final class PublicRoot {

        private PublicRoot() {}

        /**
         * Registers {@link PublicRootApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration publicRootApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    PublicRootApi.class,
                    PublicRootApi.NAME,
                    PublicRootApi.PATH,
                    List.of(CatalogResource.class),
                    false,
                    "",
                    true);
        }
    }

    /** Registers {@link ApiApp}, active, holding {@link DocsNameResource}. */
    @Module
    public static final class Api {

        private Api() {}

        /**
         * Registers {@link ApiApp}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration apiAppRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    ApiApp.class, ApiApp.NAME, ApiApp.PATH, List.of(DocsNameResource.class), false, "", true);
        }
    }
}
