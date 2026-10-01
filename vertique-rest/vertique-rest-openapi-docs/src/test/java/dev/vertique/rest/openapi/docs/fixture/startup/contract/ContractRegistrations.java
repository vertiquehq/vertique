// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import java.util.List;

/**
 * Registers {@link OwnContractPublicApi} in place of {@code PublicApi} beside the undocumented
 * {@link MgmtApi}, both active, calling {@link GeneratedRestApplicationRegistration#of} exactly as a
 * generated module does. Only {@code OwnContractPublicApi}'s registration carries a contract
 * location; {@code MgmtApi}'s stays empty, so its mount uses the global {@code jaxrs.openapiPath}.
 */
@Module
public final class ContractRegistrations {

    private ContractRegistrations() {}

    /**
     * Registers {@link OwnContractPublicApi}, active, with its own contract location.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration ownContractPublicApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                OwnContractPublicApi.class,
                OwnContractPublicApi.NAME,
                OwnContractPublicApi.PATH,
                List.of(CatalogResource.class),
                false,
                OwnContractPublicApi.OPENAPI_PATH,
                true);
    }

    /**
     * Registers {@link MgmtApi}, active, without a contract location of its own.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration mgmtApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                MgmtApi.class, MgmtApi.NAME, MgmtApi.PATH, List.of(ManagementResource.class), false, "", true);
    }
}
