// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;

/**
 * Registers the shared declarations with {@link PublicApi}'s registration inactive: the documented
 * application never mounts, and {@link MgmtApi} stays active.
 */
@Module
public final class InactivePublicRegistrationModule {

    private InactivePublicRegistrationModule() {}

    /**
     * Registers {@link PublicApi}, inactive.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration inactivePublicApiRegistration() {
        return SharedRegistrationModule.Registrations.publicApi(PublicApi.class, false);
    }

    /**
     * Registers {@link MgmtApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration mgmtApiRegistration() {
        return SharedRegistrationModule.Registrations.mgmtApi(MgmtApi.class);
    }
}
