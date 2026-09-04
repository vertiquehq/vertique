// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.dagger;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;

/** Opt-in Dagger configuration binding for named resilience policy tiers. */
@Module
public abstract class ResiliencePoliciesModule {

    private ResiliencePoliciesModule() {}

    /**
     * Provides the named resilience policy registry from the application configuration.
     *
     * <p>The provider is intentionally separate from {@link ResilienceModule}: applications that
     * install only the runtime module do not need {@code @VertxConfig} or {@link ConfigParser}
     * bindings. Install this module when the application uses {@code resilience.policies}.
     *
     * @param config the full application configuration
     * @param parser the canonical configuration parser
     * @return the application-scoped named policy registry
     */
    @Provides
    @Singleton
    static ResiliencePolicyRegistry resiliencePolicyRegistry(@VertxConfig JsonObject config, ConfigParser parser) {
        List<ResiliencePolicyConfig> policies = parser.parseKeyedObject(
                JsonConfigPaths.navigateObject(config, "resilience", "policies"), "name", ResiliencePolicyConfig.class);
        return ResiliencePolicyRegistry.of(policies);
    }
}
