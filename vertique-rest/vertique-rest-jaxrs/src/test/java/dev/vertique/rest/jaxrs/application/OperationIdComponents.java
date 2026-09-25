// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidAopApplicationRegistrationModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidAopHandBuiltMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidAopProxyResourceModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltOneMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltTwoMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidInactiveApplicationModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidZeroDeclarationResourcesModule;
import dev.vertique.rest.jaxrs.application.conflict.spy.ConflictSpyModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger components for {@link JaxRsApplicationMountConflictIT} (TP-005): six named nested
 * compositions, one per lettered case (a) to (f), proving the rest-jaxrs validator's cross-mount
 * operationId owner rule (FR-014). Every component's factory takes the deployment configuration as
 * a {@code @BindsInstance @VertxConfig JsonObject}, matching {@link ConflictDeploymentComponents}.
 * {@link ConflictSpyModule}'s two counting spies are included in every composition, so a failing
 * case can prove no router — JAX-RS or not — was ever created.
 */
public final class OperationIdComponents {

    private OperationIdComponents() {}

    /** Provisions every TP-005 composition exposes: a fresh {@link HttpVerticle} per call. */
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
     * Case (a): two applications at non-conflicting paths ({@code OpidAlphaApplication},
     * {@code OpidBetaApplication}), each listing a different resource class that declares a method
     * with operationId {@code "list"}. The two operations have no common owner, so the collision
     * must fail deployment.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.conflict.opid.GeneratedJaxRsResourcesModule.class,
                ConflictSpyModule.class
            })
    public interface OneApplicationEachListResourceComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            OneApplicationEachListResourceComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (b), a control: one resource ({@code OpidSharedListResource}) declaring {@code "list"},
     * listed by both {@code OpidShareOneApplication} and {@code OpidShareTwoApplication}. Both
     * operations share the same owner (the same resource instance), so this deploys.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.conflict.opid.GeneratedJaxRsResourcesModule.class,
                ConflictSpyModule.class
            })
    public interface SharedListResourceComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            SharedListResourceComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (c), a control: zero declarations. This component includes no
     * {@code GeneratedJaxRsApplicationRegistration} module at all, so
     * {@code Set<GeneratedJaxRsApplicationRegistration>} is empty. The default mount at
     * {@code jaxrs.basePath} ({@code /api/*}) holds one manually contributed resource declaring
     * {@code "list"}, and a hand-built {@code /other/*} mount holds another. With no registration
     * declared, only the existing per-mount rule applies, so this deploys.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                OpidZeroDeclarationResourcesModule.class,
                ConflictSpyModule.class
            })
    public interface ZeroDeclarationComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            ZeroDeclarationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (d): two applications at non-conflicting paths ({@code OpidInheritedFirstApplication},
     * {@code OpidInheritedSecondApplication}), each listing a different concrete subclass of one
     * base class that declares {@code list()}; neither subclass overrides it. The two operations
     * share the inherited method's name and parameter types but have different normalized owner
     * classes (each subclass's own {@code @Path} defeats {@code sameSurface}), so the collision
     * must fail deployment.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.conflict.opid.GeneratedJaxRsResourcesModule.class,
                ConflictSpyModule.class
            })
    public interface InheritedListMethodComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            InheritedListMethodComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (e): exactly one registration ({@code OpidInactiveApplication}'s, via
     * {@link OpidInactiveApplicationModule}, the sole module in this component contributing to
     * {@code Set<GeneratedJaxRsApplicationRegistration>}), whose condition does not match (no
     * active application), plus two hand-built JAX-RS mounts at non-conflicting paths whose
     * resources each declare a method with operationId {@code "list"}. An application is declared
     * even though none is active (AC-014.1), so the collision must fail deployment.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                OpidInactiveApplicationModule.class,
                OpidHandBuiltOneMountModule.class,
                OpidHandBuiltTwoMountModule.class,
                ConflictSpyModule.class
            })
    public interface InactiveRegistrationComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            InactiveRegistrationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Case (f), a control: {@code OpidAopApplication} lists a manual base resource
     * ({@code OpidAopBaseResource}) whose only {@code @JaxRsResources} instance is
     * {@code OpidAopProxyResource}, an AOP-shaped subclass that overrides {@code list()} marked
     * only with {@code @Override}, beside a hand-built mount at a non-conflicting path holding an
     * unproxied {@code OpidAopBaseResource} instance passed directly to
     * {@code JaxRsRouterMount.Factory#create}. Both operations normalize to the same owner class
     * ({@code OpidAopBaseResource}), so this deploys.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                OpidAopApplicationRegistrationModule.class,
                OpidAopProxyResourceModule.class,
                OpidAopHandBuiltMountModule.class,
                ConflictSpyModule.class
            })
    public interface AopProxyOverrideComponent extends Provisions {

        /** Factory taking the deployment configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the deployment configuration
             * @return the constructed component
             */
            AopProxyOverrideComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
