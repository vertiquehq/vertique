// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The public and management applications, beside {@code RestModule}, {@code JwtAuthModule} (scheme
 * {@code bearerAuth}), the documentation module, and one of {@link SharedSchemaSourceModules}.
 *
 * <ul>
 *   <li>Registers {@link StorefrontApi} and {@link BackOfficeApi} exactly as the generated
 *       registration module does ({@code GeneratedRestApplicationRegistration.of(declaringType, name,
 *       path, resources, false, "", true)}), since the annotation processor does not run on framework
 *       test sources, and contributes their resources as manual {@code @JaxRsResources} instances.
 *   <li>Provides the JWT provider of {@link SharedDeployment}, which verifies the tokens it mints.
 * </ul>
 */
@Module
public final class SharedApplicationsModule {

    private SharedApplicationsModule() {}

    /**
     * Registers {@link StorefrontApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration storefrontRegistration() {
        return GeneratedRestApplicationRegistration.of(
                StorefrontApi.class,
                StorefrontApi.NAME,
                StorefrontApi.PATH,
                List.of(StorefrontResource.class),
                false,
                "",
                true);
    }

    /**
     * Registers {@link BackOfficeApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration backOfficeRegistration() {
        return GeneratedRestApplicationRegistration.of(
                BackOfficeApi.class,
                BackOfficeApi.NAME,
                BackOfficeApi.PATH,
                List.of(BackOfficeOrderResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link StorefrontResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object storefrontResource() {
        return new StorefrontResource();
    }

    /**
     * Contributes {@link BackOfficeOrderResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object backOfficeOrderResource() {
        return new BackOfficeOrderResource();
    }

    /**
     * Provides the JWT provider the JWT authentication module's handler verifies tokens with.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return SharedDeployment.jwtAuth(vertx);
    }
}
