// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import java.util.List;

/**
 * Hand-written registration modules for the startup-check declarations, each calling
 * {@link GeneratedRestApplicationRegistration#of} exactly as a generated module does. Every nested
 * module registers one declaration; a component combines them with the shared registration modules.
 * A discovery registration ({@link Root}, {@link Empty}) must be the only registration of its
 * component, so neither is ever combined with another registration module.
 */
public final class StartupRegistrations {

    private StartupRegistrations() {}

    /** Registers the shared {@link PublicApi} alone, active, for compositions that replace {@code MgmtApi}. */
    @Module
    public static final class PublicOnly {

        private PublicOnly() {}

        /**
         * Registers {@link PublicApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration publicApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    PublicApi.class, PublicApi.NAME, PublicApi.PATH, List.of(CatalogResource.class), false, "", true);
        }
    }

    /** Registers {@link DormantApi}, inactive; add it beside the shared registrations. */
    @Module
    public static final class Dormant {

        private Dormant() {}

        /**
         * Registers {@link DormantApi}, inactive.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration dormantApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    DormantApi.class,
                    DormantApi.NAME,
                    DormantApi.PATH,
                    List.of(DormantResource.class),
                    false,
                    "",
                    false);
        }
    }

    /** Registers {@link LongNameApi}, active; add it beside the shared registrations. */
    @Module
    public static final class LongName {

        private LongName() {}

        /**
         * Registers {@link LongNameApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration longNameApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    LongNameApi.class,
                    LongNameApi.NAME,
                    LongNameApi.PATH,
                    List.of(LongNameResource.class),
                    false,
                    "",
                    true);
        }
    }

    /** Registers {@link OpsApi}, active; add it beside the shared registrations. */
    @Module
    public static final class Ops {

        private Ops() {}

        /**
         * Registers {@link OpsApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration opsApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    OpsApi.class, OpsApi.NAME, OpsApi.PATH, List.of(OpsResource.class), false, "", true);
        }
    }

    /** Registers {@link RootApi}, active, with discovery membership; it must be the sole registration module of its component. */
    @Module
    public static final class Root {

        private Root() {}

        /**
         * Registers {@link RootApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration rootApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    RootApi.class, RootApi.NAME, RootApi.PATH, List.of(), true, "", true);
        }
    }

    /** Registers {@link EmptyApi}, active, with discovery membership; it must be the sole registration module of its component. */
    @Module
    public static final class Empty {

        private Empty() {}

        /**
         * Registers {@link EmptyApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration emptyApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    EmptyApi.class, EmptyApi.NAME, EmptyApi.PATH, List.of(), true, "", true);
        }
    }

    /** Registers {@link DocumentedMgmtApi}, active; pair it with {@link PublicOnly} in place of {@code MgmtApi}. */
    @Module
    public static final class DocumentedMgmt {

        private DocumentedMgmt() {}

        /**
         * Registers {@link DocumentedMgmtApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration documentedMgmtApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    DocumentedMgmtApi.class,
                    MgmtApi.NAME,
                    MgmtApi.PATH,
                    List.of(ManagementResource.class),
                    false,
                    "",
                    true);
        }
    }

    /** Registers {@link AuthenticatedMgmtApi}, active; pair it with {@link PublicOnly} in place of {@code MgmtApi}. */
    @Module
    public static final class AuthenticatedMgmt {

        private AuthenticatedMgmt() {}

        /**
         * Registers {@link AuthenticatedMgmtApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration authenticatedMgmtApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    AuthenticatedMgmtApi.class,
                    MgmtApi.NAME,
                    MgmtApi.PATH,
                    List.of(ManagementResource.class),
                    false,
                    "",
                    true);
        }
    }

    /** Registers {@link NoSchemeApi}, active; pair it with {@link PublicOnly} in place of {@code MgmtApi}. */
    @Module
    public static final class NoScheme {

        private NoScheme() {}

        /**
         * Registers {@link NoSchemeApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration noSchemeApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    NoSchemeApi.class, MgmtApi.NAME, MgmtApi.PATH, List.of(ManagementResource.class), false, "", true);
        }
    }
}
