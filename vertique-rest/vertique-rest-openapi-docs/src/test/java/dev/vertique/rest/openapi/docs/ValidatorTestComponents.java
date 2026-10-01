// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.RecordingMountCustomizer;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.SharedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.SharedResourcesModule;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger test components of the composition-validator unit proofs. They live in the module's
 * package so a test can reach the documentation mount's package-private validated mark.
 *
 * <p>A component exposes the mounts, the composition validators, and the JAX-RS mount factory
 * without building an {@code HttpVerticle}, so a test hands hand-assembled mount lists to a
 * validator directly. Resolving the mounts creates no router.
 */
final class ValidatorTestComponents {

    private ValidatorTestComponents() {}

    /**
     * The shared fixture (the documented {@code PublicApi} and the undocumented {@code MgmtApi}, both
     * active, their resources, the documentation module, and the last marker mount) plus the
     * inactive documented {@code DormantApi}, whose resource is not contributed.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                StartupRegistrations.Dormant.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    interface DormantBesideSharedComponent {

        /**
         * Resolves the mount multibinding. The documentation mount's provider is unscoped, so every
         * call builds new mount instances, none of them marked validated.
         *
         * @return the mounts, in no particular order
         */
        Set<RouterMount> routerMounts();

        /**
         * Resolves the composition validators.
         *
         * @return the validators
         */
        Set<MountCompositionValidator> mountCompositionValidators();

        /**
         * Resolves the JAX-RS mount factory, for hand-built mounts.
         *
         * @return the factory
         */
        JaxRsRouterMount.Factory jaxRsMountFactory();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component.
             *
             * @param config the application configuration
             * @return the component
             */
            DormantBesideSharedComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
