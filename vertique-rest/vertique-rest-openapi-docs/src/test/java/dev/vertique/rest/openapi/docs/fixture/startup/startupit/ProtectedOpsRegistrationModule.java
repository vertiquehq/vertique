// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import java.util.List;

/**
 * Registers {@link ProtectedOpsApi}, active, calling {@link GeneratedRestApplicationRegistration#of}
 * exactly as a generated module does; add it beside other registrations.
 */
@Module
public final class ProtectedOpsRegistrationModule {

    private ProtectedOpsRegistrationModule() {}

    /**
     * Registers {@link ProtectedOpsApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration protectedOpsApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ProtectedOpsApi.class, OpsApi.NAME, OpsApi.PATH, List.of(OpsResource.class), false, "", true);
    }
}
