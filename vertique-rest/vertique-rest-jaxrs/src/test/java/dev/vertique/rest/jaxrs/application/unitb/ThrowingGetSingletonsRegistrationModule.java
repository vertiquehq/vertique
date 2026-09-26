// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;

/**
 * G-02's registration module in the exact C-GEN shape for {@link ThrowingGetSingletonsApplication}
 * (a no-arg-constructed application, so its registration method uses the {@code A::new} factory
 * argument), unconditionally active. Included only in its own nested component so its construction
 * never interferes with any other proof.
 */
@Module
public final class ThrowingGetSingletonsRegistrationModule {

    /**
     * Registers {@link ThrowingGetSingletonsApplication}, unconditionally active.
     *
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration throwingGetSingletonsApplicationRegistration() {
        return GeneratedJaxRsApplicationRegistration.of(
                ThrowingGetSingletonsApplication.class,
                "/api/throwing-singletons",
                true,
                ThrowingGetSingletonsApplication::new);
    }
}
