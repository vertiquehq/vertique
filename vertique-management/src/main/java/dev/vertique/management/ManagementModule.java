// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.health.HealthCheckModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module providing management endpoint configuration and SPI multibindings.
 *
 * <p>Includes {@link HealthCheckModule} to ensure the health check multibinding sets
 * are available. Declares the empty {@link ManagementEndpointContributor} multibinding
 * so the set is always satisfiable even when no contributors are registered.
 *
 * <p>Applications include this module in their Dagger {@code @Component}
 * to enable management endpoints:
 *
 * <pre>{@code
 * @Component(modules = { ..., ManagementModule.class, ... })
 * interface AppComponent {
 *     ManagementVerticle managementVerticle();
 * }
 * }</pre>
 */
@Module(includes = HealthCheckModule.class)
public abstract class ManagementModule {

    /**
     * Declares the empty default multibinding set for {@link ManagementEndpointContributor}.
     *
     * <p>This ensures the set injection point on {@link ManagementVerticle} is always
     * satisfiable even when no contributors are contributed via {@code @IntoSet}.
     *
     * @return the (potentially empty) set of contributors
     */
    @Multibinds
    abstract Set<ManagementEndpointContributor> managementEndpointContributors();

    /**
     * Provides the deserialized {@link ManagementConfig} from the {@code management} section
     * of the application configuration.
     *
     * @param config the application configuration
     * @param parser the injected config parser
     * @return the management configuration with defaults applied for any missing fields
     */
    @Provides
    @Singleton
    static ManagementConfig managementConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "management"), ManagementConfig.class);
    }
}
