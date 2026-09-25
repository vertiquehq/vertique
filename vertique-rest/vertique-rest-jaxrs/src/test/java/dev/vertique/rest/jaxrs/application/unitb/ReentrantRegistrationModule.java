// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;

/**
 * Single-proof (TP-015) registration module in the exact C-GEN shape for {@link ReentrantApplication}
 * (an {@code @Inject}-constructed application, so its registration method takes a {@code Provider}).
 * Included only in TP-015's own nested component so the re-entrant {@code Provider}-broken cycle
 * compiles without affecting any other proof's component.
 */
@Module
public final class ReentrantRegistrationModule {

    private static final PropertyCondition[] REENTRANT_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.reentrantApplication.active", "true", false)};

    /**
     * Registers {@link ReentrantApplication}, active only when
     * {@code unitb.reentrantApplication.active=true}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider constructs {@link ReentrantApplication} through its {@code @Inject}
     *                 constructor; never invoked by this factory method
     * @return the registration, active per {@link #REENTRANT_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration reentrantApplicationRegistration(
            @VertxConfig JsonObject config, Provider<ReentrantApplication> provider) {
        return GeneratedJaxRsApplicationRegistration.of(
                ReentrantApplication.class,
                "/api/reentrant",
                PropertyCondition.matchesAll(config, REENTRANT_APPLICATION_REGISTRATION_CONDITIONS),
                provider);
    }
}
