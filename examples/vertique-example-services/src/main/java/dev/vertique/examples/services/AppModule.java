// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Application-specific Dagger module providing JWT authentication and verticle deployments.
 *
 * <p>The JWT provider is consumed by {@link dev.vertique.rest.auth.jwt.JwtAuthModule}, which
 * wires it into the framework's security scheme and authorization provider multibindings.
 */
@Module
public abstract class AppModule {

    /**
     * Provides a JWT authentication provider using a symmetric HMAC key.
     *
     * <p>This example uses a hardcoded symmetric key for simplicity. Production applications
     * should use asymmetric keys and load them from secure configuration.
     *
     * @param vertx the Vert.x instance
     * @return configured JWT auth provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(
                vertx, "HS256", "super-secret-key-for-example-app-minimum-256-bits-long!!");
    }

    /**
     * Registers the management verticle for deployment in the {@link LifecyclePhase#INFRA} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    /**
     * Registers the HTTP verticle for deployment in the {@link LifecyclePhase#EDGE} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
