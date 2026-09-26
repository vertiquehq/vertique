// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;

/**
 * TP-005 (T004) case (e)'s registration module in the exact C-GEN shape for
 * {@link OpidInactiveApplication}, unconditionally inactive. The sole module contributing to
 * {@code Set<GeneratedJaxRsApplicationRegistration>} in case (e)'s component, so that set holds
 * exactly one entry: a registration whose condition does not match (AC-014.1). With no active
 * application, this registration alone still makes {@code registrations} non-empty, so the
 * cross-mount operationId scan runs over {@link OpidHandBuiltOneMountModule}'s and
 * {@link OpidHandBuiltTwoMountModule}'s mounts even though neither is an application mount.
 */
@Module
public final class OpidInactiveApplicationModule {

    private OpidInactiveApplicationModule() {}

    /**
     * Registers {@link OpidInactiveApplication}, unconditionally inactive.
     *
     * @return the registration, never active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidInactiveApplicationRegistration() {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidInactiveApplication.class, OpidInactiveApplication.PATH, false, OpidInactiveApplication::new);
    }
}
