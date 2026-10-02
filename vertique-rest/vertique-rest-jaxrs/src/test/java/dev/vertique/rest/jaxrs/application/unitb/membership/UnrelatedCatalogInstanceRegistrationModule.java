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
 * T023 L22 restoration (TP-003 case 22): registers {@link MembershipCaseApis.Case22Api},
 * unconditionally active. Paired, in its own dedicated Dagger component, with the hand-written
 * (not C-GEN-shaped) {@code Case22HandWrittenEntryModule}, whose catalog entry provider returns an
 * unrelated-type instance for every {@link Case22Resource} catalog resolution in that component —
 * never combined with {@code NullCatalogEntryModule}'s catalog entry for the same type.
 */
@Module
public final class Case22RegistrationModule {

    private Case22RegistrationModule() {}

    /**
     * Registers {@link MembershipCaseApis.Case22Api}, unconditionally active.
     *
     * @param config the application configuration (unused; this fixture is unconditional)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration case22ApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.Case22Api.class,
                "membership-case22",
                "/membership/case22",
                List.of(Case22Resource.class),
                false,
                "",
                true);
    }
}
