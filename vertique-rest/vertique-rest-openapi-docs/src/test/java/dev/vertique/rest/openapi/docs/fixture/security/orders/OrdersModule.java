// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.orders;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Registers the application {@code orders}, declared by {@link OrdersApi}, exactly as the generated
 * registration module does ({@code GeneratedRestApplicationRegistration.of(declaringType, name,
 * path, resources, false, "", true)}), since the annotation processor does not run on framework test
 * sources; contributes {@link OrderResource} as a manual {@code @JaxRsResources} instance; binds the
 * fixture scheme handlers of {@link OrderSchemeHandlers}; and provides the symmetric-key {@link
 * JWTAuth} the JWT authentication module requires.
 */
@Module
public final class OrdersModule {

    /** The HS256 signing secret of the JWT provider; no token is minted. */
    static final String SIGNING_KEY = "docs-security-test-secret-key-with-at-least-256-bits-for-hs256";

    private OrdersModule() {}

    /**
     * Registers {@link OrdersApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                OrdersApi.class, OrdersApi.NAME, OrdersApi.PATH, List.of(OrderResource.class), false, "", true);
    }

    /**
     * Contributes {@link OrderResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object orderResource() {
        return new OrderResource();
    }

    /**
     * Contributes the described {@value OrderSchemeHandlers#API_KEY_AUTH} handler.
     *
     * @return the handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler apiKeyAuthHandler() {
        return OrderSchemeHandlers.apiKeyAuth();
    }

    /**
     * Contributes the undescribed handler of the scheme only the hidden operation references.
     *
     * @return the handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler hiddenOnlyHandler() {
        return OrderSchemeHandlers.hiddenOnly();
    }

    /**
     * Contributes the described handler of the scheme no operation references.
     *
     * @return the handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler unreferencedHandler() {
        return OrderSchemeHandlers.unused();
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
