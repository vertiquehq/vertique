// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.application.unita.membership.Case22Resource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * T023 L22 restoration (G-07 (b)): registers {@link MembershipCaseApis.NullCatalogEntryApi},
 * unconditionally active, listing {@link Case22Resource} purely for its type and path. Paired, in
 * its own dedicated Dagger component, with {@code NullCatalogEntryModule}, whose hand-written
 * catalog entry's provider always returns {@code null} — never combined with
 * {@code Case22HandWrittenEntryModule}'s catalog entry for the same type, which would trip the step
 * 1 duplicate-catalog-entry check instead of exercising this row's null-instance check.
 */
@Module
public final class NullCatalogEntryRegistrationModule {

    private NullCatalogEntryRegistrationModule() {}

    /**
     * Registers {@link MembershipCaseApis.NullCatalogEntryApi}, unconditionally active.
     *
     * @param config the application configuration (unused; this fixture is unconditional)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration nullCatalogEntryApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.NullCatalogEntryApi.class,
                "membership-null-catalog-entry",
                "/membership/null-catalog-entry",
                List.of(Case22Resource.class),
                false,
                "",
                true);
    }
}
