// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * TP-007's registration module: applications {@code a}, {@code b}, and {@code c}, each
 * unconditionally active, each with one operation of a distinct id.
 */
@Module
public final class ContractRegistrationModule {

    private ContractRegistrationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration contractARegistration() {
        return GeneratedRestApplicationRegistration.of(
                ContractAApi.class, "a", "/api/a", List.of(ContractAResource.class), false, "a.yaml", true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration contractBRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ContractBApi.class, "b", "/api/b", List.of(ContractBResource.class), false, "", true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration contractCRegistration() {
        return GeneratedRestApplicationRegistration.of(
                ContractCApi.class, "c", "/api/c", List.of(ContractCResource.class), false, "c.yaml", true);
    }
}
