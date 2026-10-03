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
 * TP-009 row (b): registers {@code public} and {@code partner}, unconditionally active, at their
 * own distinct {@code openapiPath} locations ({@code public.yaml} and {@code partner.yaml}), under
 * the {@code openapi-contract} pass-through strategy. FR-027's per-mount relaxation is staged to
 * CO-4 (CX-007): distinct locations do not exempt the collision from the still-global refusal.
 */
@Module
public final class CrossMountDistinctContractRegistrationModule {

    private CrossMountDistinctContractRegistrationModule() {}

    /** {@code public}'s own contract location, distinct from {@link #PARTNER_OPENAPI_PATH}. */
    public static final String PUBLIC_OPENAPI_PATH = "public.yaml";

    /** {@code partner}'s own contract location, distinct from {@link #PUBLIC_OPENAPI_PATH}. */
    public static final String PARTNER_OPENAPI_PATH = "partner.yaml";

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration crossMountDistinctContractPublicRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                CrossMountOperationIdApis.PublicApi.class,
                "public",
                "/api/public",
                List.of(PublicListResource.class),
                false,
                PUBLIC_OPENAPI_PATH,
                true);
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration crossMountDistinctContractPartnerRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                CrossMountOperationIdApis.PartnerApi.class,
                "partner",
                "/api/partner",
                List.of(PartnerListResource.class),
                false,
                PARTNER_OPENAPI_PATH,
                true);
    }
}
