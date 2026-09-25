// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import io.vertx.core.json.JsonObject;

/**
 * TP-005 case 8's first of two separate registration modules for
 * {@link MembershipCaseApplication}, in its own module class (paired with
 * {@link MembershipDuplicateRegistrationModuleB}), so C-COMPOSE step 1's duplicate-registration
 * check ("two registrations of one class ... fail") has something to catch. Included only in
 * {@code MembershipComponents.DuplicateRegistrationComponent}, never alongside
 * {@link MembershipCaseApplicationRegistrationModule}. Two separate {@code @Provides} methods,
 * each contributing its own registration instance, are required: the generated-code contract types
 * keep identity equality (no {@code equals}/{@code hashCode}), so Dagger's set never collapses the
 * duplicate (R5).
 */
@Module
public final class MembershipDuplicateRegistrationModuleA {

    /**
     * Registers {@link MembershipCaseApplication}, unconditionally active — the first of two
     * registrations of the same class.
     *
     * @param config the application configuration (unused)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration membershipCaseApplicationRegistrationA(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                MembershipCaseApplication.class, MembershipCaseApplication.PATH, true, MembershipCaseApplication::new);
    }
}
