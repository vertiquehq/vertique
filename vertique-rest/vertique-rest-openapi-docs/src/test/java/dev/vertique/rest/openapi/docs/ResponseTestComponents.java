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
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.responses.it.ResponseApplicationModules;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the response integration tests: one component per deployment, each
 * serving exactly one application of {@link ResponseApplicationModules}.
 *
 * <p>Every component lists {@code RestModule}, {@link OpenApiDocsModule}, the canonical {@link
 * ConfigParsingModule}, {@link DocsTestSupportModule}, the {@code web-validation} wiring with the
 * canonical schema source itself ({@link DisclosureSourceModules.Canonical}), and one application
 * module, and takes the application configuration through its factory. The configuration must
 * select {@code web-validation}. The two applications named {@code notes} are never composed
 * together.
 */
final class ResponseTestComponents {

    private ResponseTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the application configuration. */
    interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /** The application {@code accounts}: an asymmetric DTO, a dynamic response, declared content. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Accounts.class
            })
    interface AccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AccountsComponent> {}
    }

    /** The application {@code notes} of {@code NotesApi}: an inferred output type with a renamed member. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Notes.class
            })
    interface NotesComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<NotesComponent> {}
    }

    /** The application {@code notesexplicit}: a declared output type with a renamed member. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.NotesExplicit.class
            })
    interface NotesExplicitComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<NotesExplicitComponent> {}
    }

    /** The application {@code receipts}: an inferred output type with a {@code @Hidden}-only field. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Receipts.class
            })
    interface ReceiptsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ReceiptsComponent> {}
    }

    /** The application {@code receiptsexplicit}: a declared output type with a {@code @Hidden}-only field. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.ReceiptsExplicit.class
            })
    interface ReceiptsExplicitComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ReceiptsExplicitComponent> {}
    }

    /** The application {@code ledger}: an output type reaching a {@code @Hidden} type. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Ledger.class
            })
    interface LedgerComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<LedgerComponent> {}
    }

    /** The application {@code fixed}: the output type whose hidden member is marked on its own field. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Fixed.class
            })
    interface FixedComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FixedComponent> {}
    }

    /** The application {@code hiddenop}: a hidden operation beside a visible one. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.HiddenOp.class
            })
    interface HiddenOpComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<HiddenOpComponent> {}
    }

    /** The application {@code pins}: an output type whose setter carries {@code @Schema(hidden = true)}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Pins.class
            })
    interface PinsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PinsComponent> {}
    }

    /** The application {@code tiers}: an output type reaching a hidden enum constant. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.Tiers.class
            })
    interface TiersComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<TiersComponent> {}
    }

    /**
     * The application {@code notes} of {@code NoteReceiptsApi}: an output type reaching a type marked
     * {@code @Schema(hidden = true)}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ResponseApplicationModules.NoteReceipts.class
            })
    interface NoteReceiptsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<NoteReceiptsComponent> {}
    }
}
