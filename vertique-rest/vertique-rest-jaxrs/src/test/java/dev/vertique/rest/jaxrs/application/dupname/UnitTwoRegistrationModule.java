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

/**
 * TP-008's registration module for {@link UnitTwoApi}, the second simulated compilation unit,
 * which registers the same name {@code api} as {@link UnitOneApi}. Included only in
 * {@code DuplicateNameComponents.DuplicateNameComponent}, never alongside
 * {@link UnitTwoRenamedRegistrationModule}.
 */
@Module
public final class UnitTwoRegistrationModule {

    private static final PropertyCondition[] UNIT_TWO_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("dupname.unitTwo.active", "true", false)};

    /**
     * Registers {@link UnitTwoApi}, active only when {@code dupname.unitTwo.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #UNIT_TWO_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration unitTwoRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                UnitTwoApi.class,
                "api",
                "/api/two",
                List.of(UnitTwoResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, UNIT_TWO_CONDITIONS));
    }
}
