// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.application.unita.membership.SubstitutedBindingResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Substituted-subclass binding check: registers {@link MembershipCaseApis.SubstitutedSubclassBindingApi},
 * unconditionally active. Paired, in its own dedicated Dagger component, with
 * {@code SubstitutedBindingCatalogModule} and {@code BindingSubstitutionModule}, whose substituted binding
 * returns a {@code SubstitutedBindingSubclassResource} instance for every {@code Provider<SubstitutedBindingResource>}
 * request in that component — a substitution no other row's component may see.
 */
@Module
public final class SubstitutedSubclassBindingRegistrationModule {

    private SubstitutedSubclassBindingRegistrationModule() {}

    /**
     * Registers {@link MembershipCaseApis.SubstitutedSubclassBindingApi}, unconditionally active.
     *
     * @param config the application configuration (unused; this fixture is unconditional)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration substitutedSubclassBindingApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.SubstitutedSubclassBindingApi.class,
                "membership-substituted-subclass-binding",
                "/membership/substituted-subclass-binding",
                List.of(SubstitutedBindingResource.class),
                false,
                "",
                true);
    }
}
