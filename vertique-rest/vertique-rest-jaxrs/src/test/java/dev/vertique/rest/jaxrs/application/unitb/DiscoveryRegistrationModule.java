// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * TP-002's registration module for {@link DiscoveryApi}. Reused, with different activation
 * configuration, across every TP-002 row: active alone (row a), active beside {@link ManagementApi}
 * (rows b and c), and inactive beside an active {@link ManagementApi} (row d) — the registration
 * itself never changes; only {@link #DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS}'s configured
 * property does.
 */
@Module
public final class DiscoveryRegistrationModule {

    private static final PropertyCondition[] DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unitb.discoveryApplication.active", "true", false)};

    /**
     * Registers {@link DiscoveryApi}, active only when
     * {@code unitb.discoveryApplication.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration discoveryApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                DiscoveryApi.class,
                "api",
                "/api",
                List.of(),
                true,
                "",
                PropertyCondition.matchesAll(config, DISCOVERY_APPLICATION_REGISTRATION_CONDITIONS));
    }
}
