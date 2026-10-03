// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.crossmount;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * TP-009 row (c): registers {@code public} and {@code partner}, unconditionally active, both with
 * an empty own {@code openapiPath} (so each falls back to the global {@code jaxrs.openapiPath},
 * {@code shared.yaml}, set by the deployment configuration), under the {@code openapi-contract}
 * pass-through strategy. FR-027's shared-contract-location clause is staged to CO-4 (CX-007): the
 * two mounts sharing one contract location does not exempt the collision from the still-global
 * refusal.
 */
@Module
public final class CrossMountSharedContractRegistrationModule {

    private CrossMountSharedContractRegistrationModule() {}

    /** The global {@code jaxrs.openapiPath} both registrations fall back to. */
    public static final String SHARED_OPENAPI_PATH = "shared.yaml";

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration crossMountSharedContractPublicRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                CrossMountOperationIdApis.PublicApi.class,
                "public",
                "/api/public",
                List.of(PublicListResource.class),
                false,
                "",
                true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration crossMountSharedContractPartnerRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                CrossMountOperationIdApis.PartnerApi.class,
                "partner",
                "/api/partner",
                List.of(PartnerListResource.class),
                false,
                "",
                true);
    }
}
