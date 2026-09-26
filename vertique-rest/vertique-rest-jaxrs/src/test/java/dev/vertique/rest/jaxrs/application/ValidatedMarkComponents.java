// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.PublicAdminMountModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link JaxRsApplicationMountConflictIT} (TP-006): two named nested
 * compositions proving the rest-jaxrs validator's validated mark and {@code createRouter}'s
 * refusal of an unvalidated application mount. Every component exposes {@code Set<RouterMount>},
 * {@code Set<MountCompositionValidator>}, and a fresh {@link HttpVerticle} built through Dagger's
 * package-private six-argument {@code @Inject} constructor (so its own composition validators
 * run). Neither multibinding, nor {@code HttpVerticle} itself, is {@code @Singleton}-scoped
 * (matching {@link DeploymentComponents}'s "unscoped" contract), so every accessor call composes a
 * fresh {@code Set<RouterMount>} with new, unmarked mount instances — the property TP-006 case (c)
 * relies on for its second, independent resolution.
 *
 * <p>Every component's factory takes the deployment configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, matching {@link ConflictDeploymentComponents} and
 * {@link OperationIdComponents}.
 */
public final class ValidatedMarkComponents {

    private ValidatedMarkComponents() {}

    /** Provisions every TP-006 composition exposes. */
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

        /**
         * Creates a new {@link HttpVerticle} through Dagger's package-private six-argument
         * {@code @Inject} constructor, resolving {@code Set<RouterMount>} (and every
         * {@code MountCompositionValidator}) fresh for this call, so its own composition
         * validators run before any of its mount routers are created.
         *
         * @return a new, Dagger-built verticle instance
         */
        HttpVerticle httpVerticle();
    }

    /**
     * Cases (a), (c), and (d): {@code unita} (for {@link
     * dev.vertique.rest.jaxrs.application.unita.CatalogResource CatalogResource} and {@link
     * dev.vertique.rest.jaxrs.application.unita.DisabledResource DisabledResource}) and
     * {@code unitb} (for {@link dev.vertique.rest.jaxrs.application.unitb.PublicApplication
     * PublicApplication}), with no conflicting mount. Case (a) and (c) configure
     * {@code test.public.classes} to the bound {@code CatalogResource}; case (d) configures it to
     * T002 TP-014's all-disabled selection, {@code DisabledResource} alone (S-004).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class
            })
    public interface PublicApplicationComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            PublicApplicationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (b): {@link PublicApplicationComponent}'s fixture set plus a hand-built, conflicting
     * mount at {@code /api/public/admin/*} ({@link PublicAdminMountModule}), which
     * {@code PublicApplication}'s {@code /api/public/*} contains.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                PublicAdminMountModule.class
            })
    public interface PublicApplicationAdminConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            PublicApplicationAdminConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
