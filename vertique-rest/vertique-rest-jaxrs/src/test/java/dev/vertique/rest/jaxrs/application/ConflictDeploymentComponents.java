// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.ApiPrefixMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.LegacyOuterMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.LegacyReportsMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.OtherMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.PublicAdminMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.PublicityMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.TenantPatternMountModule;
import dev.vertique.rest.jaxrs.application.conflict.spy.ConflictSpyModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger components for {@link JaxRsApplicationMountConflictIT} (TP-004): six named nested
 * compositions, one per lettered case (a) to (f), each pairing an application (T002's
 * {@code unitb} fixtures, reused unchanged, or T004's {@code conflict.paths.RootApplication} for
 * case (f)) or no application at all (case (c)) with one or two hand-built
 * {@code conflict.handbuilt} JAX-RS mounts, plus {@link ConflictSpyModule}'s two counting spies in
 * every composition. Every component's factory takes the deployment configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, matching {@link DeploymentComponents} and
 * {@link ConflictCompositionComponents}.
 */
public final class ConflictDeploymentComponents {

    private ConflictDeploymentComponents() {}

    /** Provisions every TP-004 composition exposes: a fresh {@link HttpVerticle} per call. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle}, resolving {@code Set<RouterMount>} (and every
         * {@code MountCompositionValidator}) fresh for this call.
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();
    }

    /**
     * Case (a): {@code ManagementApplication} at {@code /api/mgmt} beside a hand-built
     * {@code /api/*} mount ({@link ApiPrefixMountModule}) — a conflicting pair, since
     * {@code /api/} is a prefix of {@code /api/mgmt/}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                ApiPrefixMountModule.class,
                ConflictSpyModule.class
            })
    public interface ManagementApiPrefixConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            ManagementApiPrefixConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (b), the control: {@code PublicApplication} at {@code /api/public} beside a hand-built
     * {@code /api/publicity/*} mount ({@link PublicityMountModule}) — does not conflict.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                PublicityMountModule.class,
                ConflictSpyModule.class
            })
    public interface PublicPublicityNonConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            PublicPublicityNonConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (c): two hand-built nested JAX-RS mounts ({@link LegacyOuterMountModule},
     * {@link LegacyReportsMountModule}) and no application at all — only
     * {@code HttpVerticle}'s existing containment-overlap warning applies.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                LegacyOuterMountModule.class,
                LegacyReportsMountModule.class,
                ConflictSpyModule.class
            })
    public interface NestedHandBuiltNoApplicationComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            NestedHandBuiltNoApplicationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (d): {@code PublicApplication} at {@code /api/public} beside a hand-built
     * {@code /:tenant/*} pattern-path mount ({@link TenantPatternMountModule}) — conflicts with
     * every application under C-CONFLICT's pattern-path rule, regardless of any literal prefix
     * relation.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                TenantPatternMountModule.class,
                ConflictSpyModule.class
            })
    public interface PublicTenantPatternConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            PublicTenantPatternConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (e): {@code PublicApplication} at {@code /api/public} beside a hand-built
     * {@code /api/public/admin/*} mount ({@link PublicAdminMountModule}), which
     * {@code PublicApplication}'s own mount contains — the reverse direction from case (a), which
     * a one-direction-only conflict check would miss.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                PublicAdminMountModule.class,
                ConflictSpyModule.class
            })
    public interface PublicAdminReverseConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            PublicAdminReverseConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (f): the reused {@code conflict.paths.RootApplication} at the root path beside a
     * hand-built {@code /other/*} mount ({@link OtherMountModule}) — the root application
     * conflicts with every other mount.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.conflict.paths.GeneratedJaxRsResourcesModule.class,
                OtherMountModule.class,
                ConflictSpyModule.class
            })
    public interface RootApplicationConflictComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            RootApplicationConflictComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
