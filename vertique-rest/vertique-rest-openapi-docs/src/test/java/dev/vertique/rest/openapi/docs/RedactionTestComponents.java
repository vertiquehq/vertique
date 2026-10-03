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
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.RedactionApplicationModules;
import dev.vertique.rest.openapi.docs.fixture.disclosure.profile.TagsProfileModule;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.input.InputValidationModule;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the reserved-name redaction integration tests.
 *
 * <p>Every component lists {@code RestModule}, the canonical {@link ConfigParsingModule}, {@link
 * DocsTestSupportModule}, one {@code web-validation} wiring, and one application module, and takes
 * the application configuration through its factory; the configuration must select {@code
 * web-validation}, or the schema source is never asked.
 *
 * <ul>
 *   <li>A <em>serving</em> component also lists {@link OpenApiDocsModule}, so its {@link
 *       HttpVerticle} serves the application's public document.
 *   <li>A <em>rendering</em> component lists {@link ProtectedRenderingModule} instead, and the
 *       application's protected declaration: its verticle never serves a document, and its hook keeps
 *       the protected rendering. It is declared here because that hook is package-private.
 *   <li>The notes and folds components use {@link InputValidationModule} (the canonical source
 *       wrapped by a {@link RecordingSchemaSource}); the notes components also list {@link
 *       TagsProfileModule}, whose profile the notes operation selects.
 *   <li>The orders components each bind one fixture source through {@link DisclosureSourceModules};
 *       one of them lists no documentation module at all, as the reference for answers without it.
 * </ul>
 */
public final class RedactionTestComponents {

    private RedactionTestComponents() {}

    /** What every component exposes. */
    public interface VerticleProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** What a component built over the recording schema source exposes. */
    public interface RecordingProvisions extends VerticleProvisions {

        /**
         * Resolves the component's recording schema source, which wraps the canonical source.
         *
         * @return the recording source
         */
        RecordingSchemaSource recordingSource();
    }

    /** What a rendering component exposes. */
    interface RenderingProvisions extends RecordingProvisions {

        /**
         * Resolves the component's rendering hook.
         *
         * @return the hook
         */
        ProtectedRenderingPublicationHook protectedRendering();
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

    /** Serves the public document of the application {@code notes}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                TagsProfileModule.class,
                RedactionApplicationModules.Notes.class
            })
    public interface NotesComponent extends RecordingProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<NotesComponent> {}
    }

    /** Renders, without serving, the protected document of the application {@code notes}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                TagsProfileModule.class,
                RedactionApplicationModules.ProtectedNotes.class
            })
    interface ProtectedNotesComponent extends RenderingProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedNotesComponent> {}
    }

    /** Serves the public document of the application {@code folds}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                RedactionApplicationModules.Folds.class
            })
    public interface FoldsComponent extends RecordingProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FoldsComponent> {}
    }

    /** Renders, without serving, the protected document of the application {@code folds}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                RedactionApplicationModules.ProtectedFolds.class
            })
    interface ProtectedFoldsComponent extends RenderingProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedFoldsComponent> {}
    }

    /** Serves the application {@code orders} with a source whose body schemas carry no provenance. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.ManifestFree.class,
                RedactionApplicationModules.Orders.class
            })
    public interface OrdersManifestFreeComponent extends VerticleProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OrdersManifestFreeComponent> {}
    }

    /**
     * Serves the application {@code orders} with a source returning a different body carrying the
     * canonical manifest.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Replacing.class,
                RedactionApplicationModules.Orders.class
            })
    public interface OrdersReplacingComponent extends VerticleProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OrdersReplacingComponent> {}
    }

    /**
     * Serves the application {@code orders} with a source that edits the canonical body in place,
     * keeping its manifest.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.InPlaceEditing.class,
                RedactionApplicationModules.Orders.class
            })
    public interface OrdersInPlaceEditingComponent extends VerticleProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OrdersInPlaceEditingComponent> {}
    }

    /** Serves the application {@code orders} with a source attaching a string as provenance. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.ForeignProvenance.class,
                RedactionApplicationModules.Orders.class
            })
    public interface OrdersForeignProvenanceComponent extends VerticleProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OrdersForeignProvenanceComponent> {}
    }

    /**
     * Routes the application {@code orders} with a source whose body schemas carry no provenance and
     * no documentation module: the reference for how the application answers without it.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.ManifestFree.class,
                RedactionApplicationModules.Orders.class
            })
    public interface OrdersWithoutDocsComponent extends VerticleProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OrdersWithoutDocsComponent> {}
    }
}
