// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

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
 * The Dagger modules of the caching fixture. Declarations are registered exactly as the generated
 * registration module registers them ({@code GeneratedRestApplicationRegistration.of(declaringType,
 * name, path, resources, false, "", true)}), since the annotation processor does not run on framework
 * test sources, and each resource is contributed as a manual {@code @JaxRsResources} instance. Both
 * graphs compose the real JWT authentication module, which brings the framework's authentication and
 * security modules: the authentication enforcement marker, identity resolution, and the
 * authorization contributor.
 */
public final class CachingModules {

    /** The HS256 signing secret of the JWT provider; the tests mint their tokens with it. */
    public static final String SIGNING_KEY = "docs-caching-test-secret-key-with-at-least-256-bits-for-hs256";

    private CachingModules() {}

    /** Provides the symmetric-key {@link JWTAuth} the JWT authentication module requires. */
    @Module
    public static final class Jwt {

        private Jwt() {}

        /**
         * Provides the JWT provider.
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

    /**
     * The public {@code public} application beside the {@code management} application, whose document
     * the JWT bearer scheme protects with a role.
     */
    @Module(includes = Jwt.class)
    public static final class PublicAndManagement {

        private PublicAndManagement() {}

        /**
         * Registers {@link CachingPublicApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration publicApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    CachingPublicApi.class,
                    CachingPublicApi.NAME,
                    CachingPublicApi.PATH,
                    List.of(CachingResources.Catalog.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link CachingPublicApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object publicApiResource() {
            return new CachingResources.Catalog();
        }

        /**
         * Registers {@link CachingManagementApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration managementApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    CachingManagementApi.class,
                    CachingManagementApi.NAME,
                    CachingManagementApi.PATH,
                    List.of(CachingResources.Management.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link CachingManagementApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object managementApiResource() {
            return new CachingResources.Management();
        }
    }

    /**
     * One application per scheme kind, each with a protected document guarded by its kind's fixture
     * handler and no roles, beside the fixture handlers themselves.
     */
    @Module(includes = Jwt.class)
    public static final class SchemeKinds {

        private SchemeKinds() {}

        /**
         * Registers {@link UndescribedKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration undescribedApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    UndescribedKindApi.class,
                    UndescribedKindApi.NAME,
                    UndescribedKindApi.PATH,
                    List.of(CachingResources.Undescribed.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link UndescribedKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object undescribedApiResource() {
            return new CachingResources.Undescribed();
        }

        /**
         * Registers {@link HeaderKeyKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration headerKeyApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    HeaderKeyKindApi.class,
                    HeaderKeyKindApi.NAME,
                    HeaderKeyKindApi.PATH,
                    List.of(CachingResources.HeaderKey.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link HeaderKeyKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object headerKeyApiResource() {
            return new CachingResources.HeaderKey();
        }

        /**
         * Registers {@link CookieKeyKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration cookieKeyApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    CookieKeyKindApi.class,
                    CookieKeyKindApi.NAME,
                    CookieKeyKindApi.PATH,
                    List.of(CachingResources.CookieKey.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link CookieKeyKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object cookieKeyApiResource() {
            return new CachingResources.CookieKey();
        }

        /**
         * Registers {@link QueryKeyKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration queryKeyApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    QueryKeyKindApi.class,
                    QueryKeyKindApi.NAME,
                    QueryKeyKindApi.PATH,
                    List.of(CachingResources.QueryKey.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link QueryKeyKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object queryKeyApiResource() {
            return new CachingResources.QueryKey();
        }

        /**
         * Registers {@link OAuthKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration oauthApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    OAuthKindApi.class,
                    OAuthKindApi.NAME,
                    OAuthKindApi.PATH,
                    List.of(CachingResources.OAuth.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link OAuthKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object oauthApiResource() {
            return new CachingResources.OAuth();
        }

        /**
         * Registers {@link OpenIdKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration openIdApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    OpenIdKindApi.class,
                    OpenIdKindApi.NAME,
                    OpenIdKindApi.PATH,
                    List.of(CachingResources.OpenId.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link OpenIdKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object openIdApiResource() {
            return new CachingResources.OpenId();
        }

        /**
         * Registers {@link MutualTlsKindApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration mutualTlsApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    MutualTlsKindApi.class,
                    MutualTlsKindApi.NAME,
                    MutualTlsKindApi.PATH,
                    List.of(CachingResources.MutualTls.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes the resource of {@link MutualTlsKindApi}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object mutualTlsApiResource() {
            return new CachingResources.MutualTls();
        }

        /**
         * Contributes the {@value CachingSchemes#UNDESCRIBED} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler undescribedHandler() {
            return CachingSchemes.undescribed();
        }

        /**
         * Contributes the {@value CachingSchemes#HEADER_KEY} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler headerKeyHandler() {
            return CachingSchemes.headerKey();
        }

        /**
         * Contributes the {@value CachingSchemes#COOKIE_KEY} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler cookieKeyHandler() {
            return CachingSchemes.cookieKey();
        }

        /**
         * Contributes the {@value CachingSchemes#QUERY_KEY} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler queryKeyHandler() {
            return CachingSchemes.queryKey();
        }

        /**
         * Contributes the {@value CachingSchemes#OAUTH2} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler oauth2Handler() {
            return CachingSchemes.oauth2();
        }

        /**
         * Contributes the {@value CachingSchemes#OPEN_ID_CONNECT} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler openIdConnectHandler() {
            return CachingSchemes.openIdConnect();
        }

        /**
         * Contributes the {@value CachingSchemes#MUTUAL_TLS} fixture handler.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler mutualTlsHandler() {
            return CachingSchemes.mutualTls();
        }
    }
}
