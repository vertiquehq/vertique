// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Hand-written module in the exact shape the annotation processor would emit for compilation unit
 * {@code unitb}: an applications-only unit registering {@link PublicApi} and {@link ManagementApi},
 * each gated on its own {@code active} flag exactly as {@code GeneratedRestApplicationRegistration}
 * mirrors {@code @ConditionalOnProperty} (E17). {@code name}, {@code path}, and {@code resources}
 * match each interface's own {@code @RestApplication} declaration.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] PUBLIC_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.publicApplication.active", "true", false)};

    private static final PropertyCondition[] MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.managementApplication.active", "true", false)};

    /**
     * Registers {@link PublicApi}, active only when {@code unitb.publicApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #PUBLIC_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PublicApi.class,
                "public",
                "/api/public",
                List.of(ScopedResource.class, CatalogResource.class, BlobLikeResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PUBLIC_APPLICATION_REGISTRATION_CONDITIONS));
    }

    /**
     * Registers {@link ManagementApi}, active only when
     * {@code unitb.managementApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration managementApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                ManagementApi.class,
                "mgmt",
                "/api/mgmt",
                List.of(ExtraResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, MANAGEMENT_APPLICATION_REGISTRATION_CONDITIONS));
    }
}
