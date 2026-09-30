// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the shared declarations, both active, in the shape the annotation processor generates:
 * the documented {@link PublicApi} and the undocumented {@link MgmtApi}.
 */
@Module
public final class SharedRegistrationModule {

    private SharedRegistrationModule() {}

    /**
     * Registers {@link PublicApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicApiRegistration() {
        return Registrations.publicApi(PublicApi.class, true);
    }

    /**
     * Registers {@link MgmtApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration mgmtApiRegistration() {
        return Registrations.mgmtApi(MgmtApi.class);
    }

    /** Builds the fixture registrations exactly as the generated modules call the factory. */
    static final class Registrations {

        private Registrations() {}

        /**
         * Registers a declaration of application {@code public} at {@code /api/public} listing
         * {@link CatalogResource}.
         *
         * @param declaringType the declaring interface
         * @param active        whether the application is active
         * @return the registration
         */
        static GeneratedRestApplicationRegistration publicApi(Class<?> declaringType, boolean active) {
            return GeneratedRestApplicationRegistration.of(
                    declaringType, PublicApi.NAME, PublicApi.PATH, List.of(CatalogResource.class), false, "", active);
        }

        /**
         * Registers an active declaration of application {@code mgmt} at {@code /api/mgmt} listing
         * {@link ManagementResource}.
         *
         * @param declaringType the declaring interface
         * @return the registration
         */
        static GeneratedRestApplicationRegistration mgmtApi(Class<?> declaringType) {
            return GeneratedRestApplicationRegistration.of(
                    declaringType, MgmtApi.NAME, MgmtApi.PATH, List.of(ManagementResource.class), false, "", true);
        }
    }
}
