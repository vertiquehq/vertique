// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import jakarta.inject.Provider;

/**
 * G-02's registration module in the exact C-GEN shape for {@link ThrowingConstructorApplication}
 * (an {@code @Inject}-constructed application, so its registration method takes a
 * {@code Provider}), unconditionally active. Included only in its own nested component so its
 * construction never interferes with any other proof.
 */
@Module
public final class ThrowingConstructorRegistrationModule {

    /**
     * Registers {@link ThrowingConstructorApplication}, unconditionally active.
     *
     * @param provider constructs {@link ThrowingConstructorApplication} through its
     *                 {@code @Inject} constructor
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration throwingConstructorApplicationRegistration(
            Provider<ThrowingConstructorApplication> provider) {
        return GeneratedJaxRsApplicationRegistration.of(
                ThrowingConstructorApplication.class, "/api/throwing-ctor", true, provider);
    }
}
