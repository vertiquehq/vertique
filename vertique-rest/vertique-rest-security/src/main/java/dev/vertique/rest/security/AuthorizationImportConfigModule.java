// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Opt-in Dagger module that config-drives the {@link VertxAuthorizationImporter} provider deadline from
 * the {@code security.authz.importTimeoutMs} key of the application config.
 *
 * <p>Without this module, {@link VertxAuthorizationImportModule} uses {@link
 * AuthorizationImportConfig#defaults()}. The module shares the {@code security.authz} section with
 * {@link AuthorizationGateConfigModule}; each record reads only its own key.
 */
@Module
public abstract class AuthorizationImportConfigModule {

    private AuthorizationImportConfigModule() {}

    /**
     * Parses the {@code security.authz} section into an {@link AuthorizationImportConfig}.
     *
     * @param config the application configuration
     * @param parser the config parser
     * @return the parsed configuration, defaulted when the key is absent
     */
    @Provides
    @Singleton
    static AuthorizationImportConfig authorizationImportConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "security", "authz"), AuthorizationImportConfig.class);
    }
}
