// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/** TP-016 (R-004) case (d)'s registration module for {@link DisabledOnlyApi}. */
@Module
public final class DisabledOnlyApplicationRegistrationModule {

    private static final PropertyCondition[] DISABLED_ONLY_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.disabledOnlyApplication.active", "true", false)};

    /**
     * Registers {@link DisabledOnlyApi}, active only when
     * {@code unitb.disabledOnlyApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #DISABLED_ONLY_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration disabledOnlyApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                DisabledOnlyApi.class,
                "disabled-only",
                "/api/disabled-only",
                List.of(DisabledResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DISABLED_ONLY_APPLICATION_REGISTRATION_CONDITIONS));
    }
}
