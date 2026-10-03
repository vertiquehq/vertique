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
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden.HiddenApplicationModules;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the hidden-input integration tests.
 *
 * <p>Every component binds the {@code web-validation} wiring with the canonical schema source itself
 * ({@link DisclosureSourceModules.Canonical}) and no recording hook, so the schemas the gate and the
 * documents see are the generator's, and each lists exactly one application module of {@link
 * HiddenApplicationModules}. A <em>served</em> component lists {@link OpenApiDocsModule} and serves
 * the application's public document over HTTP. A <em>rendering</em> component lists {@link
 * ProtectedRenderingModule} instead, registers the application's protected twin, and exposes the
 * {@link ProtectedRenderingPublicationHook}, which keeps the protected rendering and the binding inventory; it
 * never deploys the documentation mount. The configuration must select {@code web-validation}.
 *
 * <p>To add a component: declare a {@code @Singleton @Component} listing {@code RestModule}, {@link
 * ConfigParsingModule}, {@link DocsTestSupportModule}, {@link DisclosureSourceModules.Canonical},
 * one application module, and either {@link OpenApiDocsModule} or {@link ProtectedRenderingModule},
 * extending {@link Served} or {@link Renders}, with a nested {@code @Component.Factory} extending
 * {@link Factory}.
 */
public final class HiddenInputTestComponents {

    private HiddenInputTestComponents() {}

    /** What every served component exposes. */
    public interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** What every rendering component exposes. */
    interface Renders extends Served {

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

    /** The application {@code hidden}, serving its public document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Probe.class
            })
    public interface ProbeComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProbeComponent> {}
    }

    /** The application {@code hidden}, rendering its protected document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.ProtectedProbe.class
            })
    interface ProtectedProbeComponent extends Renders {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedProbeComponent> {}
    }

    /** The application {@code accounts} with a body member carrying {@code @Hidden} only. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.HiddenField.class
            })
    public interface HiddenFieldAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<HiddenFieldAccountsComponent> {}
    }

    /** The application {@code accounts} with a body member whose type carries {@code @Hidden}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.HiddenType.class
            })
    public interface HiddenTypeAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<HiddenTypeAccountsComponent> {}
    }

    /** The application {@code accounts} with the fixed body member, serving its public document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Fixed.class
            })
    public interface FixedAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FixedAccountsComponent> {}
    }

    /** The application {@code accounts} with the fixed body member, rendering its protected document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.ProtectedFixed.class
            })
    interface ProtectedFixedAccountsComponent extends Renders {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedFixedAccountsComponent> {}
    }

    /** The application {@code accounts} with both kinds of hidden body member. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Form.class
            })
    public interface FormAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FormAccountsComponent> {}
    }

    /** The application {@code accounts} with a JavaBean body whose getter is hidden. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Bean.class
            })
    public interface BeanAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<BeanAccountsComponent> {}
    }

    /** The application {@code accounts} with a body member of an enum with a hidden constant. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Tier.class
            })
    public interface TierAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<TierAccountsComponent> {}
    }

    /** The application {@code accounts} with a body member of a hidden class. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Ledger.class
            })
    public interface LedgerAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<LedgerAccountsComponent> {}
    }

    /** The application {@code accounts} with a body whose creator parameter is hidden. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenApplicationModules.Ctor.class
            })
    public interface CtorAccountsComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<CtorAccountsComponent> {}
    }
}
