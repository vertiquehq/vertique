// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import io.vertx.core.json.JsonObject;

/**
 * Single-proof (TP-006) registration module in the exact C-GEN shape for {@link DiscoveryApplication}
 * (an application without an {@code @Inject} constructor, so its registration method uses the
 * {@code A::new} factory argument). Reused, with different activation configuration, across every
 * TP-006 case: active alone (case a), active beside {@link ManagementApplication} (cases b and c),
 * and inactive beside an active {@link ManagementApplication} (case d) — the registration itself
 * never changes; only {@link #DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS}' configured property
 * does.
 */
@Module
public final class DiscoveryRegistrationModule {

    private static final PropertyCondition[] DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.discoveryApplication.active", "true", false)};

    /**
     * Registers {@link DiscoveryApplication}, active only when
     * {@code unitb.discoveryApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration discoveryApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                DiscoveryApplication.class,
                "/api",
                PropertyCondition.matchesAll(config, DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS),
                DiscoveryApplication::new);
    }
}
