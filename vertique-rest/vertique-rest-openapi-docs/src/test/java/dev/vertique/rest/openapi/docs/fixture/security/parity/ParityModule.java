// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.parity;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Registers the application {@code parity}, declared by {@link ParityApi}, exactly as the generated
 * registration module does, since the annotation processor does not run on framework test sources;
 * contributes the resources of {@link ParityResources} as manual {@code @JaxRsResources} instances;
 * and provides the symmetric-key {@link JWTAuth} the JWT authentication module requires.
 */
@Module
public final class ParityModule {

    /** The HS256 signing secret of the JWT provider; the test mints its tokens with it. */
    public static final String SIGNING_KEY = "docs-parity-test-secret-key-with-at-least-256-bits-for-hs256";

    private ParityModule() {}

    /**
     * Registers {@link ParityApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                ParityApi.class,
                ParityApi.NAME,
                ParityApi.PATH,
                List.of(
                        ParityResources.DocumentLevel.class,
                        ParityResources.ClassLevel.class,
                        ParityResources.PublicClass.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link ParityResources.DocumentLevel}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object documentLevel() {
        return new ParityResources.DocumentLevel();
    }

    /**
     * Contributes {@link ParityResources.ClassLevel}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object classLevel() {
        return new ParityResources.ClassLevel();
    }

    /**
     * Contributes {@link ParityResources.PublicClass}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object publicClass() {
        return new ParityResources.PublicClass();
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
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", SIGNING_KEY);
    }
}
