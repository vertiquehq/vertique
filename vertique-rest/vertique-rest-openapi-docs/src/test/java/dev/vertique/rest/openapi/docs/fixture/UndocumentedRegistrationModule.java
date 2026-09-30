// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;

/**
 * Registers the shared declarations with {@link UndocumentedPublicApi} in place of
 * {@link PublicApi}: both applications active, neither documented.
 */
@Module
public final class UndocumentedRegistrationModule {

    private UndocumentedRegistrationModule() {}

    /**
     * Registers {@link UndocumentedPublicApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration undocumentedPublicApiRegistration() {
        return SharedRegistrationModule.Registrations.publicApi(UndocumentedPublicApi.class, true);
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
