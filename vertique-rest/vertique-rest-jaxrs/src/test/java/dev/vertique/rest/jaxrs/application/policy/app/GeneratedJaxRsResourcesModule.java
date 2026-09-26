// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy.app;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import jakarta.inject.Provider;

/**
 * Hand-written module in the exact C-GEN shape for compilation unit {@code policy.app} (T005): an
 * applications-only unit, so (per C-GEN's package-resolution rule) it keeps the same simple name,
 * {@code GeneratedJaxRsResourcesModule}, as {@code policy}'s resources-only module, in this unit's
 * own package. It registers {@link ManagementApplication} ({@code @Inject}-constructed, so its
 * registration method takes a {@code Provider}). This is the sole registration a T005
 * application-mount composition needs, so — unlike {@code application.unitb}, whose two
 * applications coexist and need a per-application activation gate — this registration is
 * unconditionally active; the application-mount vs. legacy-default-mount choice is made by which
 * {@code ExplicitPolicyComponents} component a test builds, not by configuration.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    /**
     * Registers {@link ManagementApplication}, unconditionally active.
     *
     * @param provider constructs {@link ManagementApplication} through its {@code @Inject}
     *                 constructor
     * @return the registration, always active
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration managementApplicationRegistration(
            Provider<ManagementApplication> provider) {
        return GeneratedJaxRsApplicationRegistration.of(ManagementApplication.class, "/api/mgmt", true, provider);
    }
}
