// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * T023 L22 restoration (TP-003 case 14, the step 1 duplicate-catalog-entry check): registers
 * {@link MembershipCaseApis.DuplicateCatalogApi}, unconditionally active (the C-GEN
 * {@code <conditions>} literal {@code true}, matching a fixture without
 * {@code @ConditionalOnProperty}). Paired, in its own dedicated Dagger component, with the two
 * separate catalog-entry modules for {@link DuplicateCatalogResource}
 * ({@code DuplicateCatalogEntryModuleA} and {@code DuplicateCatalogEntryModuleB}); the composer's
 * step 1 duplicate check runs over the whole component's catalog before any registration's
 * membership is evaluated, so this fixture never shares a component with any other row.
 */
@Module
public final class DuplicateCatalogRegistrationModule {

    private DuplicateCatalogRegistrationModule() {}

    /**
     * Registers {@link MembershipCaseApis.DuplicateCatalogApi}, unconditionally active.
     *
     * @param config the application configuration (unused; this fixture is unconditional)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration duplicateCatalogApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.DuplicateCatalogApi.class,
                "membership-duplicate-catalog",
                "/membership/duplicate-catalog",
                List.of(DuplicateCatalogResource.class),
                false,
                "",
                true);
    }
}
