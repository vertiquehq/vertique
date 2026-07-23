// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Application-specific Dagger module providing configuration and authentication setup.
 *
 * <p>Configures JWT authentication with a symmetric HMAC key (HS256) for demonstration
 * purposes. Production applications should use asymmetric keys (RS256) and load them
 * from secure configuration.
 *
 * <p>The {@link JWTAuth} instance provided here is consumed by
 * {@link dev.vertique.rest.auth.jwt.JwtAuthModule}, which wires it into the framework's
 * security scheme handler and authorization provider multibindings automatically.
 */
@Module
@Slf4j
public class AppModule {

    @Singleton
    @Provides
    HelloConfig helloConfig(@VertxConfig JsonObject jsonObject, ConfigParser parser) {
        log.info("Mapping HelloConfig");
        return parser.parse(jsonObject, HelloConfig.class);
    }

    /**
     * Provides a JWT authentication provider using a symmetric HMAC key.
     *
     * <p><b>Warning:</b> This example uses a hardcoded symmetric key for simplicity.
     * Production applications should use JWKS:
     * <pre>{@code
     * return JwtAuthFactory.fromJwks(vertx, "classpath:jwks.json");
     * return JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json");
     * }</pre>
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

    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
