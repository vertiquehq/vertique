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
 * TP-005 case 8's second of two separate registration modules for
 * {@link MembershipCaseApplication}; see {@link MembershipDuplicateRegistrationModuleA}.
 */
@Module
public final class MembershipDuplicateRegistrationModuleB {

    /**
     * Registers {@link MembershipCaseApplication}, unconditionally active — the second of two
     * registrations of the same class.
     *
     * @param config the application configuration (unused)
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration membershipCaseApplicationRegistrationB(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                MembershipCaseApplication.class, MembershipCaseApplication.PATH, true, MembershipCaseApplication::new);
    }
}
