// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.conflict.paths.GeneratedJaxRsResourcesModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger component for {@link JaxRsApplicationMountConflictTest} (TP-003), built from the
 * {@code conflict.paths} compilation unit's seven overriding applications (T004's
 * {@code …app/conflict/paths/} fixtures). Every application registration is always present, so
 * composer step 1 (registration checks) always runs over the full set; each
 * {@code @MethodSource} row activates exactly the two applications its case names through the
 * config's boolean activation flags, so step 1b sees only that pair as active and every other
 * application stays registered but inactive.
 */
public final class ConflictCompositionComponents {

    private ConflictCompositionComponents() {}

    /**
     * TP-003's sole fixture set: only the {@code conflict.paths} unit, so no unrelated
     * membership, catalog, or manual-resource interaction can produce a violation other than the
     * deliberate path conflict (or, for the control, none at all).
     */
    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, GeneratedJaxRsResourcesModule.class})
    public interface ConflictPathsComponent {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, running composer step 1 (including
         * step 1b).
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
             * @param config the application configuration, carrying this case's activation flags
             * @return the constructed component
             */
            ConflictPathsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
