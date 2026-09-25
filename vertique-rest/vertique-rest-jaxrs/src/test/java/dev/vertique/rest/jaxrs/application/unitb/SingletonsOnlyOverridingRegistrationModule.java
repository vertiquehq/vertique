// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;

/**
 * G-04 (b)'s registration module in the exact C-GEN shape for
 * {@link SingletonsOnlyOverridingApplication} (a no-arg-constructed application, so its
 * registration method uses the {@code A::new} factory argument), unconditionally active.
 */
@Module
public final class SingletonsOnlyOverridingRegistrationModule {

    /**
     * Registers {@link SingletonsOnlyOverridingApplication}, unconditionally active.
     *
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration singletonsOnlyOverridingApplicationRegistration() {
        return GeneratedJaxRsApplicationRegistration.of(
                SingletonsOnlyOverridingApplication.class,
                "/api/singletons-only",
                true,
                SingletonsOnlyOverridingApplication::new);
    }
}
