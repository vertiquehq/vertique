// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket;

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
 * Application-specific Dagger module for the WebSocket chat example.
 *
 * <p>Provides JWT authentication using a symmetric HMAC key and registers the management and HTTP
 * verticles for phased deployment. The {@link JWTAuth} instance is consumed by
 * {@link dev.vertique.rest.auth.jwt.JwtAuthModule} to wire bearer-token authentication into the
 * security handler chain.
 *
 * <p><b>Warning:</b> The symmetric key below is a demo-only constant. Production applications
 * should use asymmetric keys loaded from secure configuration (e.g. JWKS endpoint).
 */
@Module
public abstract class AppModule {

    /**
     * Demo symmetric key used for signing and verifying HS256 tokens in this example.
     *
     * <p>Package-private so that the integration test ({@link WebSocketChatIT}) can construct an
     * equivalent {@link JWTAuth} for token generation without depending on the live Dagger graph.
     */
    static final String DEMO_JWT_SECRET = "super-secret-key-for-example-app-minimum-256-bits-long!!";

    /**
     * Provides the JWT authentication provider using a symmetric HMAC key.
     *
     * @param vertx the Vert.x instance
     * @return configured JWT auth provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", DEMO_JWT_SECRET);
    }

    /**
     * Contributes the management verticle to the deployment multibinding, scheduled in the INFRA
     * phase so health endpoints are available before the HTTP server starts.
     *
     * @param provider lazy provider for the management verticle
     * @return deployment descriptor for the management verticle
     */
    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    /**
     * Contributes the HTTP verticle to the deployment multibinding, scheduled in the EDGE phase
     * after infrastructure verticles are running.
     *
     * @param provider lazy provider for the HTTP verticle
     * @return deployment descriptor for the HTTP verticle
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
