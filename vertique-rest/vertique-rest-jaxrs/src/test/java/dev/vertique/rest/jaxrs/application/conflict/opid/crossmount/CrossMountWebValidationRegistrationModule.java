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
 * TP-009 row (a): registers {@code public} and {@code partner}, unconditionally active, with no
 * contract location (irrelevant under {@code web-validation}, which never reports
 * {@code resolvesOperationsFromMountContract()}).
 */
@Module
public final class CrossMountWebValidationRegistrationModule {

    private CrossMountWebValidationRegistrationModule() {}

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration crossMountWebValidationPublicRegistration(
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
    static GeneratedRestApplicationRegistration crossMountWebValidationPartnerRegistration(
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
