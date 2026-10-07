// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.SecurityModule;
import jakarta.inject.Singleton;

/**
 * The Dagger compositions of the typed synthetic operation proofs, all in a package outside
 * {@code dev.vertique.rest.jaxrs} so resolving the installer exercises the same cross-package path
 * a documentation module uses.
 *
 * <p>{@link Legacy} is the supported composition, the framework's own {@code RestModule},
 * {@code AuthModule} and {@code SecurityModule} with a bearer scheme, a route authentication handler,
 * an action registry and a counting authorizer. Every other composition omits exactly one capability
 * of it, or all of the security modules, so a refusal can only come from that omission.
 */
public final class TypedSyntheticComponents {

    private TypedSyntheticComponents() {}

    /** What every composition exposes. */
    public interface Composition {

        /**
         * Returns the public installer of the composition.
         *
         * @return the installer
         */
        SyntheticOperationInstaller syntheticOperationInstaller();

        /**
         * Returns what the composition's fixtures observed.
         *
         * @return the observations
         */
        TypedSyntheticObservations observations();
    }

    /** A composition that can be deployed and requested over HTTP. */
    public interface Deployable extends Composition {

        /**
         * Creates the verticle composing the mounts the composition contributes.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** The supported composition with the two legacy operations mounted at {@code /legacy}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.RegisteredActions.class,
                TypedSyntheticModules.CountingAuthorizer.class,
                LegacyMounts.class
            })
    public interface Legacy extends Deployable {}

    /** No security modules at all, with the bearer scheme handler present. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.NoSecurity.class,
                TypedSyntheticModules.BearerScheme.class
            })
    public interface WithoutSecurityModules extends Composition {}

    /** The supported composition without any security scheme handler. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.RegisteredActions.class,
                TypedSyntheticModules.CountingAuthorizer.class
            })
    public interface WithoutSchemeHandler extends Composition {}

    /** The supported composition without any route authentication handler. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.RegisteredActions.class,
                TypedSyntheticModules.CountingAuthorizer.class
            })
    public interface WithoutRouteAuthHandler extends Composition {}

    /** The supported composition with two route authentication handlers. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.SecondRouteAuthHandler.class,
                TypedSyntheticModules.RegisteredActions.class,
                TypedSyntheticModules.CountingAuthorizer.class
            })
    public interface WithTwoRouteAuthHandlers extends Composition {}

    /** The supported composition whose action registry lacks the fixture action. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.EmptyActions.class,
                TypedSyntheticModules.CountingAuthorizer.class
            })
    public interface WithUnregisteredAction extends Composition {}

    /** The supported composition with an action registry but no authorizer. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class,
                TypedSyntheticModules.RegisteredActions.class
            })
    public interface WithoutAuthorizer extends Composition {}

    /** The supported composition with neither an action registry nor an authorizer. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                AuthModule.class,
                SecurityModule.class,
                TypedSyntheticModules.ConfigSupport.class,
                TypedSyntheticModules.Observing.class,
                TypedSyntheticModules.BearerScheme.class,
                TypedSyntheticModules.OneRouteAuthHandler.class
            })
    public interface WithoutAuthorizationEngine extends Composition {}

    /** Contributes the legacy operations mount to {@link Legacy}. */
    @Module
    static final class LegacyMounts {

        private LegacyMounts() {}

        @Provides
        @IntoSet
        static RouterMount legacyMount(
                SyntheticOperationInstaller operations, TypedSyntheticObservations observations) {
            return new TypedLegacyMount(operations, observations);
        }
    }
}
