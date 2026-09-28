// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/** TP-008's registration module for {@link UnitOneApi}, one simulated compilation unit. */
@Module
public final class UnitOneRegistrationModule {

    private static final PropertyCondition[] UNIT_ONE_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("dupname.unitOne.active", "true", false)};

    /**
     * Registers {@link UnitOneApi}, active only when {@code dupname.unitOne.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #UNIT_ONE_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration unitOneRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                UnitOneApi.class,
                "api",
                "/api/one",
                List.of(UnitOneResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, UNIT_ONE_CONDITIONS));
    }
}
