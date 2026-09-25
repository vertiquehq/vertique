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

/**
 * Single-proof (TP-016) registration module in the exact C-GEN shape for
 * {@link ThirdOverridingApplication} (an application without an {@code @Inject} constructor, so its
 * registration method uses the {@code A::new} factory argument). Its configured activation key is
 * never set by {@code JaxRsApplicationCompositionTest}, so the registration is always inactive.
 */
@Module
public final class ThirdOverridingRegistrationModule {

    private static final PropertyCondition[] THIRD_OVERRIDING_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.thirdOverridingApplication.active", "true", false)};

    /**
     * Registers {@link ThirdOverridingApplication}, active only when
     * {@code unitb.thirdOverridingApplication.active=true} — a key this suite never sets.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per
     *     {@link #THIRD_OVERRIDING_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration thirdOverridingApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                ThirdOverridingApplication.class,
                "/api/third",
                PropertyCondition.matchesAll(config, THIRD_OVERRIDING_APPLICATION_REGISTRATION_CONDITIONS),
                ThirdOverridingApplication::new);
    }
}
