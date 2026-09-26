// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.manual.ManualResourceModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Compile-time-only Dagger component validating that the shared C-GEN-shaped fixture modules
 * ({@code unita}'s resources-only module, {@code unitb}'s applications-only module, and
 * {@link ManualResourceModule}) resolve against {@link RestModule}'s new
 * {@code Set<GeneratedJaxRsApplicationRegistration>} and {@code Set<GeneratedJaxRsResourceEntry>}
 * {@code @Multibinds} declarations, and against {@link ApplicationTestSupportModule}'s stand-ins.
 *
 * <p>This component has no test method: it is exercised only by annotation processing during
 * {@code mvn compile}/{@code test-compile}. Later lanes ({@code JaxRsApplicationCompositionTest}
 * and its sibling suites) may reuse this shape or replace it with their own components.
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
public interface SharedFixtureSmokeComponents {

    /**
     * Resolves the {@code Set<RouterMount>} multibinding, forcing Dagger to validate every
     * contribution reachable from it: {@code RestModule}'s default mount provider,
     * {@code @JaxRsResources} (including {@code unita}'s bindings and {@code manual}'s
     * contribution), and, transitively, {@code Set<GeneratedJaxRsApplicationRegistration>}
     * (including {@code unitb}'s registrations).
     *
     * @return the resolved mount set
     */
    Set<RouterMount> routerMounts();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component bound to the given configuration.
         *
         * @param config the application configuration
         * @return the constructed component
         */
        SharedFixtureSmokeComponents create(@BindsInstance @VertxConfig JsonObject config);
    }
}
