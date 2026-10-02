// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.Component;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.openapi.docs.StartupTestComponents.CompositionExtensions;
import dev.vertique.rest.openapi.docs.StartupTestComponents.Factory;
import dev.vertique.rest.openapi.docs.StartupTestComponents.StartupBase;
import dev.vertique.rest.openapi.docs.StartupTestComponents.StartupProvisions;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.StartupApplications;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup.StartupSchemeHandlers;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger test components of the misconfigured-protected-document startup test. Each lists
 * {@link StartupBase}, one {@code management} declaration with one defect, at most one valid
 * protected application beside it, and one scheme-handler combination. Each exposes, besides what a
 * startup component exposes, the composition validators and the router mounts, so a test can mark a
 * documentation mount as the composition does and build it on its own router.
 */
public final class ProtectedStartupTestComponents {

    private ProtectedStartupTestComponents() {}

    /** What every component of this class exposes. */
    public interface ProtectedStartupGraph extends StartupProvisions, CompositionExtensions {

        /**
         * Provisions the router mounts, new unscoped mount instances on every call.
         *
         * @return the mounts
         */
        Set<RouterMount> routerMounts();
    }

    /**
     * {@code management} names the scheme {@code nope}, which no handler registers; the enforcement
     * marker is bound and no scheme handler.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.UnknownSchemeManagement.class,
                StartupSchemeHandlers.EnforcementOnly.class
            })
    public interface UnknownSchemeComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<UnknownSchemeComponent> {}
    }

    /**
     * {@code management} names the scheme {@code emptyAuth}, whose registered handler configures no
     * authentication handler; the enforcement marker is bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.HandlerlessSchemeManagement.class,
                StartupSchemeHandlers.HandlerlessWithEnforcement.class
            })
    public interface HandlerlessSchemeComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<HandlerlessSchemeComponent> {}
    }

    /**
     * {@code management} names the registered scheme {@code bearerAuth} and the role {@code admin};
     * the enforcement marker is not bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.RoleManagement.class,
                StartupSchemeHandlers.AuthenticatingWithoutEnforcement.class
            })
    public interface RoleWithoutEnforcementComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<RoleWithoutEnforcementComponent> {}
    }

    /**
     * {@code management} names the registered scheme {@code bearerAuth} and no role; the enforcement
     * marker is not bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.RolelessManagement.class,
                StartupSchemeHandlers.AuthenticatingWithoutEnforcement.class
            })
    public interface RolelessWithoutEnforcementComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<RolelessWithoutEnforcementComponent> {}
    }

    /**
     * {@code management} names the scheme {@code emptyAuth} beside the valid protected document of
     * {@code alpha}, whose name orders it first; both handlers and the enforcement marker are bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.Alpha.class,
                StartupApplications.HandlerlessSchemeManagement.class,
                StartupSchemeHandlers.BothWithEnforcement.class
            })
    public interface AlphaBesideHandlerlessComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AlphaBesideHandlerlessComponent> {}
    }

    /**
     * {@code management} names the scheme {@code emptyAuth} beside the valid protected document of
     * {@code zeta}, whose name orders it last; both handlers and the enforcement marker are bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupApplications.Zeta.class,
                StartupApplications.HandlerlessSchemeManagement.class,
                StartupSchemeHandlers.BothWithEnforcement.class
            })
    public interface ZetaBesideHandlerlessComponent extends ProtectedStartupGraph {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ZetaBesideHandlerlessComponent> {}
    }
}
