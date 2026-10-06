// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed;

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
 * The fixtures of a deployment that holds exactly one documented application, {@value
 * TypedStartupApis#NAME}, whose declaring interface the test chooses, beside the real JWT
 * authentication module. The application is registered exactly as the generated registration module
 * does, and its resource is contributed as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class TypedSingleDocumentModule {

    private TypedSingleDocumentModule() {}

    /**
     * The declaring interface of the one application.
     *
     * @param type the declaring interface, one of the nested interfaces of {@link TypedStartupApis}
     */
    public record Declaration(Class<?> type) {}

    /**
     * Provides the JWT provider the JWT authentication module's handler verifies tokens with.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", TypedDocumentModule.SIGNING_KEY);
    }

    /**
     * Registers the declared application.
     *
     * @param declaration the declaring interface
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration(Declaration declaration) {
        return GeneratedRestApplicationRegistration.of(
                declaration.type(),
                TypedStartupApis.NAME,
                TypedStartupApis.PATH,
                List.of(TypedStartupApis.Items.class),
                false,
                "",
                true);
    }

    /**
     * Contributes the application's resource.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object items() {
        return new TypedStartupApis.Items();
    }
}
