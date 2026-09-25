// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;

/**
 * G-04 (a)'s registration module in the exact C-GEN shape for {@link InheritingApplication} (a
 * no-arg-constructed application, so its registration method uses the {@code A::new} factory
 * argument), unconditionally active.
 */
@Module
public final class InheritingApplicationRegistrationModule {

    /**
     * Registers {@link InheritingApplication}, unconditionally active.
     *
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration inheritingApplicationRegistration() {
        return GeneratedJaxRsApplicationRegistration.of(
                InheritingApplication.class, "/api/inheriting", true, InheritingApplication::new);
    }
}
