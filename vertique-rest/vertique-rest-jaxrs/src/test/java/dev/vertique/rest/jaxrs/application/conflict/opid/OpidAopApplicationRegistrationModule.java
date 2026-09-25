// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;

/**
 * TP-005 (T004) case (f)'s registration module in the exact C-GEN shape for
 * {@link OpidAopApplication} (an application without an {@code @Inject} constructor, so its
 * registration method uses the {@code A::new} factory argument), unconditionally active.
 */
@Module
public final class OpidAopApplicationRegistrationModule {

    private OpidAopApplicationRegistrationModule() {}

    /**
     * Registers {@link OpidAopApplication}, unconditionally active.
     *
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidAopApplicationRegistration() {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidAopApplication.class, OpidAopApplication.PATH, true, OpidAopApplication::new);
    }
}
