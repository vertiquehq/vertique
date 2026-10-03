// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.input.InputValidationModule;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingInventoryPublicationHook;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.input.a.PublicApplicationModule;
import dev.vertique.rest.openapi.docs.fixture.input.b.PartnerApplicationModule;
import dev.vertique.rest.openapi.docs.fixture.input.it.ItApplicationModules;
import dev.vertique.rest.openapi.docs.fixture.input.it.TestValidatorModule;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.PatternsApplicationModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the input-assembly integration tests.
 *
 * <p>Every component is built from {@code RestModule}, {@link OpenApiDocsModule}, the canonical
 * {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and {@link InputValidationModule} (the
 * {@code web-validation} strategy, the canonical schema source wrapped by a {@link
 * RecordingSchemaSource}, an accepting {@code BeanValidator}, and a {@link RecordingInventoryPublicationHook}
 * beside the documentation hook), plus the modules registering its declared applications and
 * contributing their resources. A component whose proof needs authored {@code @Pattern} flags rendered
 * into the captured patterns also lists {@link TestValidatorModule}, which binds a real Bean
 * Validation {@code Validator}. Each takes the application configuration through its factory; the
 * configuration must select {@code web-validation}, or the schema source is never asked. Every
 * component exposes {@link InputProvisions}.
 *
 * <p>To add a component: declare a {@code @Singleton @Component} listing the five shared modules, an
 * application module, and {@link TestValidatorModule} when flags must be rendered, extending {@link
 * InputProvisions}, with a nested {@code @Component.Factory} extending {@link Factory}.
 */
public final class InputTestComponents {

    private InputTestComponents() {}

    /** What every component exposes. */
    public interface InputProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component, which serves the documentation mount.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's recording schema source, which wraps the canonical source.
         *
         * @return the recording source
         */
        RecordingSchemaSource recordingSource();

        /**
         * Resolves the component's recording inventory hook.
         *
         * @return the recording hook
         */
        RecordingInventoryPublicationHook recordingHook();
    }

    /** Creates a component from the application configuration. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /**
     * The twin applications {@code generated} and {@code reflected}: one search operation each, with
     * identical bindings, described by the generated descriptor path and the reflective scanner.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                ItApplicationModules.Twins.class
            })
    public interface TwinsComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<TwinsComponent> {}
    }

    /**
     * The application {@code verbatim}: one operation whose body member and query parameter carry
     * authored patterns, with a real {@code Validator} bound so the member pattern's flags are
     * rendered into its captured pattern.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                TestValidatorModule.class,
                ItApplicationModules.Verbatim.class
            })
    public interface VerbatimComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<VerbatimComponent> {}
    }

    /**
     * The application {@code patterns}: a case-insensitively bound body, a body with an authored
     * flagged pattern and a nested type described as a root local definition, and an operation with a
     * path and a query parameter carrying authored patterns; a real {@code Validator} is bound so the
     * flags are rendered into the captured pattern. Its three operation ids are distinct.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                TestValidatorModule.class,
                PatternsApplicationModule.class
            })
    public interface PatternsComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PatternsComponent> {}
    }

    /**
     * The application {@code frozen}: two operations with body and parameter schemas, one body
     * carrying root local definitions.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                ItApplicationModules.Frozen.class
            })
    public interface FrozenComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FrozenComponent> {}
    }

    /**
     * The two applications {@code public} and {@code partner}: each lists one resource with a
     * {@code GET} and a {@code POST}, whose bodies share a simple name across two packages, and every
     * operation id is distinct across both mounts.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                PublicApplicationModule.class,
                PartnerApplicationModule.class
            })
    public interface TwoApplicationsComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<TwoApplicationsComponent> {}
    }
}
