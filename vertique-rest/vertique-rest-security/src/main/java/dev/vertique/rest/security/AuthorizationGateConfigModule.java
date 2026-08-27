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
 * Opt-in Dagger module that config-drives {@link AuthorizationGateConfig} — the {@link
 * SecurityPolicyEnforcer#decide} role/scope and action gate deadline — from the {@code
 * security.authz} section of the application config (issue #417, R42).
 *
 * <p>{@link AuthModule} declares {@code Optional<AuthorizationGateConfig>} via {@code
 * @BindsOptionalOf}, defaulting to {@link AuthorizationGateConfig#defaults()} when no module binds
 * one — installing this module (or binding {@link AuthorizationGateConfig} programmatically some
 * other way) is how an application overrides the default deadline.
 *
 * <p>Config path: {@code security.authz} — for example:
 *
 * <pre>{@code
 * security:
 *   authz:
 *     gateDeadlineMs: 5000
 * }</pre>
 *
 * <p>Include this module alongside {@link SecurityModule} / {@link AuthModule} in any Dagger
 * {@code @Component} that should config-drive the gate deadline instead of using the framework
 * default.
 */
@Module
public abstract class AuthorizationGateConfigModule {

    private AuthorizationGateConfigModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the parsed {@link AuthorizationGateConfig} from the {@code security.authz} section
     * of the application config.
     *
     * @param config the full application configuration injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed, validated gate-deadline configuration; never {@code null}
     */
    @Provides
    @Singleton
    static AuthorizationGateConfig authorizationGateConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "security", "authz"), AuthorizationGateConfig.class);
    }
}
