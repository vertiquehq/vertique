// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.dupname.DupnameResourcesModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitOneRegistrationModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitTwoRegistrationModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitTwoRenamedRegistrationModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/** Dagger components for {@link JaxRsApplicationCompositionTest}'s TP-008. */
public final class DuplicateNameComponents {

    private DuplicateNameComponents() {}

    /** Provisions every duplicate-name-suite component exposes. */
    public interface Provisions {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();
    }

    /**
     * TP-008 rows (a) to (c): both {@code UnitOneApi} and {@code UnitTwoApi} registered, each
     * naming {@code api}; only their active flags vary per row.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                UnitOneRegistrationModule.class,
                UnitTwoRegistrationModule.class
            })
    public interface DuplicateNameComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DuplicateNameComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-008's control row: {@code UnitOneApi} (naming {@code api}) beside
     * {@code UnitTwoRenamedApi} (naming {@code api-two}), so the names no longer collide. Each
     * listed resource is manually bound ({@link DupnameResourcesModule}), so this row composes
     * instead of failing on an unbound class.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                UnitOneRegistrationModule.class,
                UnitTwoRenamedRegistrationModule.class,
                DupnameResourcesModule.class
            })
    public interface RenamedControlComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            RenamedControlComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
