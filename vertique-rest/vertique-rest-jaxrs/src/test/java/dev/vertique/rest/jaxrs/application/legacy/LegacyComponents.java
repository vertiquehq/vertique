// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.legacy;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components pinning the rest-024 branch-start zero-declaration composition.
 *
 * <p>{@link ResourceAndManualComponent} includes the legacy-shaped
 * {@link GeneratedJaxRsResourcesModule} (no registration-set parameter, no catalog entries — the
 * shape a module produced by an older processor takes, D002) plus {@link ManualResourceModule}'s
 * hand-written contribution — the shape sibling framework modules use. {@link EmptyComponent}
 * includes neither, pinning the empty-resource-set path (TP-003).
 *
 * <p>Precedent: {@code RestSanitizationComponentTest} compiles
 * {@code @Component(modules = RestModule.class)} directly in this test source set without any
 * extra wiring. The OQ-001 prototype proved this exact
 * {@code @BindsInstance @VertxConfig JsonObject} factory shape, the unsecured
 * {@code SecurityPolicyValidator} stand-in, and the minimal {@code ConfigParser} compile and
 * resolve a full {@code HttpVerticle} graph on {@code 104b8c2f} (evidence/oq001-proof.md,
 * evidence/oq001-prototype.patch).
 */
public final class LegacyComponents {

    private LegacyComponents() {}

    /** Provisions every characterization component exposes. */
    public interface Provisions {

        /**
         * Resolves the {@code @JaxRsResources Set<Object>} multibinding.
         *
         * @return the resolved resource set
         */
        @JaxRsResources
        Set<Object> jaxRsResources();

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, including {@code RestModule}'s
         * default JAX-RS mount provider.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /**
         * Creates a new {@link HttpVerticle}. Unscoped: every call composes {@code Set<RouterMount>}
         * (and therefore {@code @JaxRsResources}) again, matching one composition per verticle
         * instance (plan pre-flight finding 1).
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();
    }

    /** The legacy-shaped generated module plus the manual sibling-framework-module contribution. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                LegacySupportModule.class,
                GeneratedJaxRsResourcesModule.class,
                ManualResourceModule.class
            })
    public interface ResourceAndManualComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ResourceAndManualComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Neither the legacy resource module nor the manual module: pins the empty-set path (TP-003). */
    @Singleton
    @Component(modules = {RestModule.class, LegacySupportModule.class})
    public interface EmptyComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            EmptyComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
