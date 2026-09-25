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
 * Hand-written module in the exact C-GEN shape for compilation unit {@code unitb}: an
 * applications-only unit, so (per C-GEN's package-resolution rule) it keeps the same simple name,
 * {@code GeneratedJaxRsResourcesModule}, as {@code unita}'s resources-only module, in this unit's
 * own package. It registers {@link PublicApplication} (an {@code @Inject}-constructed application,
 * so its registration method takes a {@code Provider}) and {@link ManagementApplication} (a
 * no-arg-constructed application, so its registration method uses the {@code A::new} factory
 * argument instead). Both applications conceptually carry a
 * {@code @ConditionalOnProperty}-equivalent activation gate — see each registration method's
 * condition constant — mirrored the same way {@code unita}'s {@code DisabledResource} is, since
 * {@code vertique-codegen-core} is not a test dependency of this module.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] PUBLIC_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.publicApplication.active", "true", false)};

    private static final PropertyCondition[] MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.managementApplication.active", "true", false)};

    /**
     * Registers {@link PublicApplication}, active only when
     * {@code unitb.publicApplication.active=true}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider constructs {@link PublicApplication} through its {@code @Inject} constructor
     * @return the registration, active per {@link #PUBLIC_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration publicApplicationRegistration(
            @VertxConfig JsonObject config, Provider<PublicApplication> provider) {
        return GeneratedJaxRsApplicationRegistration.of(
                PublicApplication.class,
                "/api/public",
                PropertyCondition.matchesAll(config, PUBLIC_APPLICATION_REGISTRATION_CONDITIONS),
                provider);
    }

    /**
     * Registers {@link ManagementApplication}, active only when
     * {@code unitb.managementApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration managementApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                ManagementApplication.class,
                "/api/mgmt",
                PropertyCondition.matchesAll(config, MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS),
                ManagementApplication::new);
    }
}
