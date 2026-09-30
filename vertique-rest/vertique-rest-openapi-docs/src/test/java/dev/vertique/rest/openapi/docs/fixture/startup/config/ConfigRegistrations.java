// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.startup.DormantApi;
import dev.vertique.rest.openapi.docs.fixture.startup.DormantResource;
import dev.vertique.rest.openapi.docs.fixture.startup.LongNameApi;
import dev.vertique.rest.openapi.docs.fixture.startup.LongNameResource;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;
import java.util.List;

/**
 * Hand-written registrations for the configuration views, each one call of
 * {@link GeneratedRestApplicationRegistration#of} exactly as a generated module makes it. A view is
 * built from a list of these registrations.
 */
public final class ConfigRegistrations {

    private ConfigRegistrations() {}

    /**
     * Registers application {@code public} at {@code /api/public} listing {@link CatalogResource},
     * active, declared by the given interface.
     *
     * @param declaringType the declaring interface; its {@code @RestApplication} names {@code public}
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration publicApi(Class<?> declaringType) {
        return GeneratedRestApplicationRegistration.of(
                declaringType, PublicApi.NAME, PublicApi.PATH, List.of(CatalogResource.class), false, "", true);
    }

    /**
     * Registers the undocumented {@link MgmtApi}, active.
     *
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration mgmtApi() {
        return GeneratedRestApplicationRegistration.of(
                MgmtApi.class, MgmtApi.NAME, MgmtApi.PATH, List.of(ManagementResource.class), false, "", true);
    }

    /**
     * Registers the undocumented {@link LongNameApi}, active.
     *
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration longNameApi() {
        return GeneratedRestApplicationRegistration.of(
                LongNameApi.class,
                LongNameApi.NAME,
                LongNameApi.PATH,
                List.of(LongNameResource.class),
                false,
                "",
                true);
    }

    /**
     * Registers the documented {@link DormantApi}, inactive.
     *
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration dormantApi() {
        return GeneratedRestApplicationRegistration.of(
                DormantApi.class, DormantApi.NAME, DormantApi.PATH, List.of(DormantResource.class), false, "", false);
    }

    /**
     * Registers application {@code ops} at {@code /api/ops} listing {@link OpsResource}, declared by
     * the given interface.
     *
     * @param declaringType the declaring interface; its {@code @RestApplication} names {@code ops}
     * @param active        whether the application is active
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration opsApi(Class<?> declaringType, boolean active) {
        return GeneratedRestApplicationRegistration.of(
                declaringType, OpsApi.NAME, OpsApi.PATH, List.of(OpsResource.class), false, "", active);
    }
}
