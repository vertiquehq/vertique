// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusResource;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.PatternsResource;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingModules;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingSchemes;
import dev.vertique.rest.openapi.docs.fixture.responses.it.AccountResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.FixedReceiptResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.HiddenOperationResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ReportResource;
import dev.vertique.rest.openapi.docs.fixture.responses.it.SnakeOutputProfileModule;
import java.util.List;

/**
 * The Dagger modules registering the corpus applications, in the shape the annotation processor
 * generates, and contributing their resources as manual {@code @JaxRsResources} instances.
 *
 * <p>The applications are split over two kinds of composition: {@link PublicMain} and {@link
 * ProtectedMain} hold {@code corpus}, {@code responses}, and {@code schemes}; {@link PublicPatterns}
 * and {@link ProtectedPatterns} hold {@code patterns} alone, because only that application's proof
 * needs a real Bean Validation {@code Validator} bound (to render authored pattern flags), and the
 * other applications are kept on the binding they were written against. Every operation id is
 * distinct within a composition, and no resource class appears twice in one.
 *
 * <p>{@link Main} and {@link Patterns} hold what their applications need besides the registrations
 * (the JWT provider for every composition; the scheme handlers and the output profile for the main
 * one) and are included by the registration modules.
 */
public final class CorpusApplicationModules {

    private CorpusApplicationModules() {}

    private static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, List<Class<?>> resources) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, resources, false, "", true);
    }

    /**
     * What every composition holding {@code corpus}, {@code responses}, and {@code schemes} needs: the
     * JWT provider, the described scheme handlers of {@link CachingSchemes} (the JWT bearer handler
     * comes from the JWT module), the output profile of the responses, and the resources.
     */
    @Module(includes = {CachingModules.Jwt.class, SnakeOutputProfileModule.class})
    public static final class Main {

        private Main() {}

        /**
         * Contributes the request-body corpus resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object corpusResource() {
            return new CorpusResource();
        }

        /**
         * Contributes the resource whose responses use a non-default output profile.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object accountResource() {
            return new AccountResource();
        }

        /**
         * Contributes the resource with a dynamic and an explicitly declared response.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object reportResource() {
            return new ReportResource();
        }

        /**
         * Contributes the resource whose response type's hiding markers the generator honors.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object fixedReceiptResource() {
            return new FixedReceiptResource();
        }

        /**
         * Contributes the resource with a hidden operation beside a visible one.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenOperationResource() {
            return new HiddenOperationResource();
        }

        /**
         * Contributes the resource with one operation per described scheme kind.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object schemeKindsResource() {
            return new SchemeKindsResource();
        }

        /**
         * The API key scheme sent in a header.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler headerKeyHandler() {
            return CachingSchemes.headerKey();
        }

        /**
         * The API key scheme sent in a cookie.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler cookieKeyHandler() {
            return CachingSchemes.cookieKey();
        }

        /**
         * The API key scheme sent in the query.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler queryKeyHandler() {
            return CachingSchemes.queryKey();
        }

        /**
         * The OAuth 2 scheme.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler oauth2Handler() {
            return CachingSchemes.oauth2();
        }

        /**
         * The OpenID Connect scheme.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler openIdConnectHandler() {
            return CachingSchemes.openIdConnect();
        }

        /**
         * The mutual TLS scheme.
         *
         * @return the handler
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler mutualTlsHandler() {
            return CachingSchemes.mutualTls();
        }
    }

    /** What every composition holding {@code patterns} needs: the JWT provider and the resource. */
    @Module(includes = CachingModules.Jwt.class)
    public static final class Patterns {

        private Patterns() {}

        /**
         * Contributes the patterns resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object patternsResource() {
            return new PatternsResource();
        }
    }

    /** Registers {@code corpus}, {@code responses}, and {@code schemes}, each with a public document. */
    @Module(includes = Main.class)
    public static final class PublicMain {

        private PublicMain() {}

        /**
         * Registers {@code corpus}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration corpus() {
            return register(
                    PublicCorpusApi.class,
                    CorpusDocuments.CORPUS,
                    CorpusDocuments.CORPUS_PATH,
                    List.of(CorpusResource.class));
        }

        /**
         * Registers {@code responses}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration responses() {
            return register(
                    PublicResponsesApi.class,
                    CorpusDocuments.RESPONSES,
                    CorpusDocuments.RESPONSES_PATH,
                    List.of(
                            AccountResource.class,
                            ReportResource.class,
                            FixedReceiptResource.class,
                            HiddenOperationResource.class));
        }

        /**
         * Registers {@code schemes}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration schemes() {
            return register(
                    PublicSchemesApi.class,
                    CorpusDocuments.SCHEMES,
                    CorpusDocuments.SCHEMES_PATH,
                    List.of(SchemeKindsResource.class));
        }
    }

    /** Registers {@code corpus}, {@code responses}, and {@code schemes}, each with a protected document. */
    @Module(includes = Main.class)
    public static final class ProtectedMain {

        private ProtectedMain() {}

        /**
         * Registers {@code corpus}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration corpus() {
            return register(
                    ProtectedCorpusApi.class,
                    CorpusDocuments.CORPUS,
                    CorpusDocuments.CORPUS_PATH,
                    List.of(CorpusResource.class));
        }

        /**
         * Registers {@code responses}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration responses() {
            return register(
                    ProtectedResponsesApi.class,
                    CorpusDocuments.RESPONSES,
                    CorpusDocuments.RESPONSES_PATH,
                    List.of(
                            AccountResource.class,
                            ReportResource.class,
                            FixedReceiptResource.class,
                            HiddenOperationResource.class));
        }

        /**
         * Registers {@code schemes}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration schemes() {
            return register(
                    ProtectedSchemesApi.class,
                    CorpusDocuments.SCHEMES,
                    CorpusDocuments.SCHEMES_PATH,
                    List.of(SchemeKindsResource.class));
        }
    }

    /** Registers {@code patterns} with a public document. */
    @Module(includes = Patterns.class)
    public static final class PublicPatterns {

        private PublicPatterns() {}

        /**
         * Registers {@code patterns}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration patterns() {
            return register(
                    PublicPatternsApi.class,
                    CorpusDocuments.PATTERNS,
                    CorpusDocuments.PATTERNS_PATH,
                    List.of(PatternsResource.class));
        }
    }

    /** Registers {@code patterns} with a protected document. */
    @Module(includes = Patterns.class)
    public static final class ProtectedPatterns {

        private ProtectedPatterns() {}

        /**
         * Registers {@code patterns}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration patterns() {
            return register(
                    ProtectedPatternsApi.class,
                    CorpusDocuments.PATTERNS,
                    CorpusDocuments.PATTERNS_PATH,
                    List.of(PatternsResource.class));
        }
    }
}
