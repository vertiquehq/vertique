// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link ExplicitSecurityPolicyTest} (TP-002 and TP-004): two named
 * compositions built from the {@code policy} resources unit and {@link
 * dev.vertique.rest.jaxrs.application.policy.PolicySecurityModule PolicySecurityModule}, mirroring
 * {@link ValidatedMarkComponents}'s shape (T004) — every component exposes both
 * {@code Set<RouterMount>} and {@code Set<MountCompositionValidator>}, so a test can run the
 * component's own composition validators before {@code createRouter}, as {@code HttpVerticle.start}
 * does, satisfying the validated mark an application mount's {@code createRouter} otherwise refuses
 * (C-CONFLICT, T004).
 *
 * <p>Every component's factory takes the application configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, matching {@link CompositionComponents} and
 * {@link ValidatedMarkComponents}.
 */
public final class ExplicitPolicyComponents {

    private ExplicitPolicyComponents() {}

    /** Provisions every T005 composition exposes. */
    public interface Provisions {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, fresh for this call.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /**
         * Resolves the {@code Set<MountCompositionValidator>} multibinding.
         *
         * @return the resolved validator set
         */
        Set<MountCompositionValidator> mountCompositionValidators();
    }

    /**
     * The application-mount composition (TP-002): {@code policy}'s resources unit,
     * {@code policy.app}'s {@code ManagementApplication} registration, and
     * {@code PolicySecurityModule}. Exactly one application mount ({@code /api/mgmt/*}) is
     * produced; which resource variant it hosts is selected by configuration (the enabled
     * {@code policy.<variant>.enabled} gate and {@code policy.app.classes}).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.policy.PolicySecurityModule.class,
                dev.vertique.rest.jaxrs.application.policy.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.policy.app.GeneratedJaxRsResourcesModule.class
            })
    public interface ApplicationMountComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ApplicationMountComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * The zero-declaration, legacy-default-mount composition (TP-004): {@code policy}'s resources
     * unit and {@code PolicySecurityModule} only — no application registration module at all, so
     * {@code Set<GeneratedJaxRsApplicationRegistration>} resolves empty and the sole resource
     * variant a test enables reaches the zero-declaration default mount ({@code /*}) instead.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.policy.PolicySecurityModule.class,
                dev.vertique.rest.jaxrs.application.policy.GeneratedJaxRsResourcesModule.class
            })
    public interface LegacyDefaultMountComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            LegacyDefaultMountComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
