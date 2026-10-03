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
 * TP-008's control registration module for {@link UnitTwoRenamedApi}, whose name no longer
 * collides with {@link UnitOneApi}'s. Included only in
 * {@code DuplicateNameComponents.RenamedControlComponent}, never alongside
 * {@link UnitTwoRegistrationModule}.
 */
@Module
public final class UnitTwoRenamedRegistrationModule {

    private static final PropertyCondition[] UNIT_TWO_RENAMED_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("dupname.unitTwo.active", "true", false)};

    /**
     * Registers {@link UnitTwoRenamedApi}, active only when {@code dupname.unitTwo.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #UNIT_TWO_RENAMED_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration unitTwoRenamedRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                UnitTwoRenamedApi.class,
                "api-two",
                "/api/two",
                List.of(UnitTwoResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, UNIT_TWO_RENAMED_CONDITIONS));
    }
}
