// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;

/**
 * Registers the shared declarations with {@link ProtectedMgmtApi} in place of {@link MgmtApi}: the
 * public document of {@link PublicApi} beside the protected document of {@code mgmt}, both active.
 */
@Module
public final class ProtectedRegistrationModule {

    private ProtectedRegistrationModule() {}

    /**
     * Registers {@link PublicApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicApiRegistration() {
        return SharedRegistrationModule.Registrations.publicApi(PublicApi.class, true);
    }

    /**
     * Registers {@link ProtectedMgmtApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration protectedMgmtApiRegistration() {
        return SharedRegistrationModule.Registrations.mgmtApi(ProtectedMgmtApi.class);
    }
}
