// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * The applications of the misconfigured-protected-document fixtures, each a nested module that
 * registers one active declaration, in the shape the annotation processor generates, and
 * contributes its one unsecured resource as a manual {@code @JaxRsResources} instance. A component
 * includes exactly one {@code management} module and at most one other application.
 */
public final class StartupApplications {

    /** The name of the misconfigured application. */
    public static final String MANAGEMENT = "management";

    /** The mount path of every {@code management} declaration. */
    public static final String MANAGEMENT_PATH = "/api/management";

    private StartupApplications() {}

    /** Registers {@code management} declared by {@link UnknownSchemeManagementApi}. */
    @Module
    public static final class UnknownSchemeManagement {

        private UnknownSchemeManagement() {}

        /**
         * Registers {@link UnknownSchemeManagementApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return management(UnknownSchemeManagementApi.class);
        }

        /**
         * Contributes the {@code management} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ManagementStatusResource();
        }
    }

    /** Registers {@code management} declared by {@link HandlerlessSchemeManagementApi}. */
    @Module
    public static final class HandlerlessSchemeManagement {

        private HandlerlessSchemeManagement() {}

        /**
         * Registers {@link HandlerlessSchemeManagementApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return management(HandlerlessSchemeManagementApi.class);
        }

        /**
         * Contributes the {@code management} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ManagementStatusResource();
        }
    }

    /** Registers {@code management} declared by {@link RoleManagementApi}. */
    @Module
    public static final class RoleManagement {

        private RoleManagement() {}

        /**
         * Registers {@link RoleManagementApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return management(RoleManagementApi.class);
        }

        /**
         * Contributes the {@code management} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ManagementStatusResource();
        }
    }

    /** Registers {@code management} declared by {@link RolelessManagementApi}. */
    @Module
    public static final class RolelessManagement {

        private RolelessManagement() {}

        /**
         * Registers {@link RolelessManagementApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return management(RolelessManagementApi.class);
        }

        /**
         * Contributes the {@code management} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ManagementStatusResource();
        }
    }

    /** Registers {@code alpha} declared by {@link AlphaApi}, a valid protected document. */
    @Module
    public static final class Alpha {

        private Alpha() {}

        /**
         * Registers {@link AlphaApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    AlphaApi.class, "alpha", "/api/alpha", List.of(AlphaStatusResource.class), false, "", true);
        }

        /**
         * Contributes the {@code alpha} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new AlphaStatusResource();
        }
    }

    /** Registers {@code zeta} declared by {@link ZetaApi}, a valid protected document. */
    @Module
    public static final class Zeta {

        private Zeta() {}

        /**
         * Registers {@link ZetaApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    ZetaApi.class, "zeta", "/api/zeta", List.of(ZetaStatusResource.class), false, "", true);
        }

        /**
         * Contributes the {@code zeta} resource.
         *
         * @return the resource
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ZetaStatusResource();
        }
    }

    /** Builds an active {@code management} registration listing {@link ManagementStatusResource}. */
    private static GeneratedRestApplicationRegistration management(Class<?> declaringType) {
        return GeneratedRestApplicationRegistration.of(
                declaringType, MANAGEMENT, MANAGEMENT_PATH, List.of(ManagementStatusResource.class), false, "", true);
    }
}
