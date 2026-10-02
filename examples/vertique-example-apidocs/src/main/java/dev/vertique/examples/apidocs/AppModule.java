// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Application-specific Dagger module: the JWT authentication provider and the HTTP verticle
 * deployment.
 */
@Module
public abstract class AppModule {

    private AppModule() {}

    /**
     * Provides the JWT authentication provider from the symmetric HMAC key configured at
     * {@code jwt.hs256Key}.
     *
     * <p>The key is configuration, never source: the shipped {@code config/application.json} holds
     * none, so a deployment supplies its own.
     *
     * <p><b>Warning:</b> a shared symmetric key suits an example. Production applications should
     * verify tokens against the issuer's published keys instead:
     * <pre>{@code
     * return JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json");
     * }</pre>
     *
     * @param vertx  the Vert.x instance
     * @param config the application configuration
     * @return the JWT authentication provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx, @VertxConfig JsonObject config) {
        JsonObject jwt = config.getJsonObject("jwt", new JsonObject());
        String key = jwt.getString("hs256Key");
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", key);
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
