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
 * Hand-written module in the exact C-GEN shape registering {@link MembershipCaseApplication},
 * always active (the C-GEN {@code <conditions>} literal {@code true}, since this fixture carries
 * no {@code @ConditionalOnProperty}). Used by every TP-005 case (and TP-018) that reuses
 * {@link MembershipCaseApplication} with a single registration; cases 8's two registrations of the
 * same class use {@link MembershipDuplicateRegistrationModuleA} and
 * {@link MembershipDuplicateRegistrationModuleB} instead.
 */
@Module
public final class MembershipCaseApplicationRegistrationModule {

    /**
     * Registers {@link MembershipCaseApplication}, unconditionally active.
     *
     * @param config the application configuration (unused; this fixture is unconditional, matching
     *               C-GEN's shape for an application without {@code @ConditionalOnProperty})
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration membershipCaseApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                MembershipCaseApplication.class, MembershipCaseApplication.PATH, true, MembershipCaseApplication::new);
    }
}
