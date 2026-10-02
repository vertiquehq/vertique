// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.root;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * What both root-application components share: {@link StatusResource} as a manual
 * {@code @JaxRsResources} instance, which the root application's discovery membership selects; the
 * symmetric-key {@link JWTAuth} the JWT authentication module verifies tokens with; and the
 * {@link DecisionRecorder} as a security event observer. The registration of the declaration is
 * contributed by {@link RolesPresent} or {@link RolesAbsent}, exactly as the generated registration
 * module does ({@code GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(),
 * true, "", true)}), since the annotation processor does not run on framework test sources.
 */
@Module
public final class RootApplicationModule {

    /** The HS256 signing secret of the JWT provider; tests mint their tokens with it. */
    public static final String SIGNING_KEY = "protected-root-docs-test-secret-key-at-least-256-bits-hs256";

    /** The signing algorithm of the JWT provider. */
    public static final String ALGORITHM = "HS256";

    private RootApplicationModule() {}

    /**
     * Contributes {@link StatusResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object statusResource() {
        return new StatusResource();
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
        return JwtAuthFactory.fromSymmetricKey(vertx, ALGORITHM, SIGNING_KEY);
    }

    /**
     * Contributes the component's decision recorder as a security event observer.
     *
     * @param recorder the recorder
     * @return the same recorder
     */
    @Provides
    @IntoSet
    static SecurityEventObserver decisionObserver(DecisionRecorder recorder) {
        return recorder;
    }

    /** Registers {@link InternalAdminApi}, whose document lists a role, active. */
    @Module
    public static final class RolesPresent {

        private RolesPresent() {}

        /**
         * Registers {@link InternalAdminApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    InternalAdminApi.class, InternalAdminApi.NAME, InternalAdminApi.PATH, List.of(), true, "", true);
        }
    }

    /** Registers {@link InternalAuthenticatedApi}, whose document lists no role, active. */
    @Module
    public static final class RolesAbsent {

        private RolesAbsent() {}

        /**
         * Registers {@link InternalAuthenticatedApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    InternalAuthenticatedApi.class,
                    InternalAdminApi.NAME,
                    InternalAdminApi.PATH,
                    List.of(),
                    true,
                    "",
                    true);
        }
    }
}
