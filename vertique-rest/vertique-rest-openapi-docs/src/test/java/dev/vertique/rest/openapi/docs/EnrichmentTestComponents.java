// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.InputTestComponents.Factory;
import dev.vertique.rest.openapi.docs.InputTestComponents.InputProvisions;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.input.InputValidationModule;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.EnrichmentApplicationModules;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the metadata-enrichment integration tests.
 *
 * <p>Every component is built from {@code RestModule}, {@link OpenApiDocsModule}, the canonical
 * {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and {@link InputValidationModule} (the
 * {@code web-validation} strategy, the canonical schema source wrapped by a recording source, an
 * accepting {@code BeanValidator}, and a recording inventory sink), plus one module registering its
 * declared application and contributing its resource. None binds a Bean Validation {@code
 * Validator}, so the canonical source describes bodies without one. Each takes the application
 * configuration through its factory and exposes {@link InputProvisions}.
 *
 * <p>The two catalog applications share their operation ids, which one composition refuses across
 * its mounts, so each has a component of its own.
 */
public final class EnrichmentTestComponents {

    private EnrichmentTestComponents() {}

    /** The application {@code gen}: the catalog twin the generated descriptor path describes. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                EnrichmentApplicationModules.Gen.class
            })
    public interface GenComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<GenComponent> {}
    }

    /** The application {@code refl}: the catalog twin the reflective scanner describes. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                EnrichmentApplicationModules.Refl.class
            })
    public interface ReflComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ReflComponent> {}
    }

    /** The application {@code shop}: one operation taking a body with renamed, documented members. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InputValidationModule.class,
                EnrichmentApplicationModules.Shop.class
            })
    public interface ShopComponent extends InputProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ShopComponent> {}
    }
}
