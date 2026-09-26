// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.manual.ManualResourceModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger components for {@link JaxRsApplicationDeploymentIT} (TP-010 to TP-013), built from L01's
 * shared {@code unita} and {@code unitb} fixture modules, reusing the existing
 * {@link dev.vertique.rest.jaxrs.application.unitb.PublicApplication PublicApplication} and
 * {@link dev.vertique.rest.jaxrs.application.unitb.ManagementApplication ManagementApplication}
 * configuration switches (activation flags, {@code test.public.classes}, {@code test.public.mode})
 * for every TP given, so no IT-only registration module is needed (§ Scope: "if that suffices").
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
     * (PublicApplication, ManagementApplication). Used by TP-010, TP-011, and TP-013, whose Given
     * needs only these two registrations, configured per case.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class
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
     * The standard fixture set plus {@code manual} (BlobLike), so TP-012's manual contribution
     * exists as a candidate the unselected report can name.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
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
}
