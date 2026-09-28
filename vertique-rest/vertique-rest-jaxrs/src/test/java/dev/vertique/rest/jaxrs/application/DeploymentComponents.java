// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.contract.ContractRegistrationModule;
import dev.vertique.rest.jaxrs.application.contract.ContractResourcesModule;
import dev.vertique.rest.jaxrs.application.contract.ContractStrategyModule;
import dev.vertique.rest.jaxrs.application.manual.ManualResourceModule;
import dev.vertique.rest.jaxrs.application.strategy.OpenApiContractPassThroughStrategy;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger components for {@link JaxRsApplicationDeploymentIT}, built from the shared {@code unita}
 * and {@code unitb} fixture modules, reusing the native
 * {@link dev.vertique.rest.jaxrs.application.unitb.PublicApi PublicApi} and
 * {@link dev.vertique.rest.jaxrs.application.unitb.ManagementApi ManagementApi} registrations'
 * activation flags for every given, so no IT-only registration module is needed for those two.
 * Every component's factory takes the application configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, matching {@link CompositionComponents} and T001's
 * {@code LegacyComponents}.
 */
public final class DeploymentComponents {

    private DeploymentComponents() {}

    /** Provisions every deployment-suite component exposes: a fresh {@link HttpVerticle} per call. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle}. Unscoped: every call composes {@code Set<RouterMount>}
         * again, matching one composition per verticle instance (plan pre-flight finding 1), so
         * {@code new DeploymentOptions().setInstances(n)} triggers {@code n} independent compositions.
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();
    }

    /**
     * {@code unita} ({@link dev.vertique.rest.jaxrs.application.unita.CatalogResource CatalogResource},
     * {@link dev.vertique.rest.jaxrs.application.unita.ExtraResource ExtraResource}) and {@code unitb}
     * ({@code PublicApi}, {@code ManagementApi}). {@code PublicApi} also lists
     * {@link dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource ScopedResource} and the
     * manual {@link dev.vertique.rest.jaxrs.application.manual.BlobLikeResource BlobLikeResource}, so
     * both {@code unita.scoped}'s catalog module and {@link ManualResourceModule} are included too,
     * or membership resolution for {@code PublicApi} would fail.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                ManualResourceModule.class
            })
    public interface StandardComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            StandardComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * The same fixture set as {@link StandardComponent}, so TP-012's manual contribution exists as a
     * candidate the unselected report can name.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                ManualResourceModule.class
            })
    public interface UnselectedReportComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            UnselectedReportComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-007's fixture set: applications {@code a}, {@code b}, and {@code c}
     * ({@link ContractRegistrationModule}), each with one operation of a distinct id, each resource
     * manually bound ({@link ContractResourcesModule}), deployed under the recording
     * {@link OpenApiContractPassThroughStrategy} ({@link ContractStrategyModule}).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                ContractRegistrationModule.class,
                ContractResourcesModule.class,
                ContractStrategyModule.class
            })
    public interface ContractLocationComponent extends Provisions {

        /**
         * Resolves the same {@code @Singleton} strategy instance bound into
         * {@code Set<RequestValidationStrategy>}, so the test can read its recorded locations.
         *
         * @return the recording strategy
         */
        OpenApiContractPassThroughStrategy openApiContractPassThroughStrategy();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ContractLocationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
