// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

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
 *
 * <p>Note: the API base path and OpenAPI document path come from the typed {@code JaxRsConfig}
 * provided by {@link dev.vertique.rest.core.dagger.RestCoreModule} (defaults {@code "/*"} and
 * {@code "openapi.json"}, overridable via the {@code jaxrs.basePath} / {@code jaxrs.openapiPath}
 * config keys); no override is needed here.
 */
@Module
public class AppModule {

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

    /**
     * Contributes the HTTP verticle to the deployment multibinding, scheduled in the EDGE phase.
     *
     * @param provider lazy provider for {@link HttpVerticle}
     * @return the verticle deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
