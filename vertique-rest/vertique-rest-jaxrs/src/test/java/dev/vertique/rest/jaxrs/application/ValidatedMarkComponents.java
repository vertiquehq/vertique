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
     * Cases (a) and (c): {@code unita}'s catalog and {@code unitb}'s
     * {@link dev.vertique.rest.jaxrs.application.unitb.PublicApi PublicApi}, with no conflicting
     * mount, activated by {@code unitb.publicApplication.active}. Case (d) (R-004) instead activates
     * {@link dev.vertique.rest.jaxrs.application.unitb.DisabledOnlyApi DisabledOnlyApi}, whose sole
     * listed class is a catalog entry disabled by default, so its mount has zero resources: the
     * {@code createRouter} refusal this case proves must run before the empty-mount early return,
     * not merely for a mount that also happens to carry resources.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.DisabledOnlyApplicationRegistrationModule.class,
                dev.vertique.rest.jaxrs.application.manual.ManualResourceModule.class
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
     * {@code unitb.PublicApi}'s {@code /api/public/*} contains.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.manual.ManualResourceModule.class,
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
