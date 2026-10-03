// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.conformance.corpus.CorpusApplicationModules;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.input.it.TestValidatorModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the conformance corpus integration test.
 *
 * <p>Every component is built from {@code RestModule}, {@link OpenApiDocsModule}, the canonical
 * {@link ConfigParsingModule}, the JWT module (the {@code bearerAuth} handler, which guards every
 * protected document and one operation, and the real security policy validator), and the canonical
 * {@code web-validation} schema source with an accepting {@code BeanValidator}. The configuration
 * must select {@code web-validation}, or the schema source is never asked and no input schema is
 * published. The patterns components also bind a real Bean Validation {@code Validator}, so the
 * authored pattern flags are rendered into the published pattern; the main components do not.
 *
 * <p>The public and the protected declaration of one application run in different components, so a
 * composition never holds the same resource class twice.
 */
final class ConformanceCorpusTestComponents {

    private ConformanceCorpusTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component, which serves the documentation mount.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the Vert.x instance and the application configuration. */
    interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param vertx  the Vert.x instance the JWT provider is built on
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }

    /** {@code corpus}, {@code responses}, and {@code schemes}, each with a public document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                DisclosureSourceModules.Canonical.class,
                CorpusApplicationModules.PublicMain.class
            })
    interface PublicMainComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PublicMainComponent> {}
    }

    /** {@code corpus}, {@code responses}, and {@code schemes}, each with a protected document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                DisclosureSourceModules.Canonical.class,
                CorpusApplicationModules.ProtectedMain.class
            })
    interface ProtectedMainComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedMainComponent> {}
    }

    /** {@code patterns} with a public document, with a real Bean Validation {@code Validator}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                DisclosureSourceModules.Canonical.class,
                TestValidatorModule.class,
                CorpusApplicationModules.PublicPatterns.class
            })
    interface PublicPatternsComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PublicPatternsComponent> {}
    }

    /** {@code patterns} with a protected document, with a real Bean Validation {@code Validator}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                DisclosureSourceModules.Canonical.class,
                TestValidatorModule.class,
                CorpusApplicationModules.ProtectedPatterns.class
            })
    interface ProtectedPatternsComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedPatternsComponent> {}
    }
}
