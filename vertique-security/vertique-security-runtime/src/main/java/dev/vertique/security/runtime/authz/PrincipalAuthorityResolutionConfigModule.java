// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Opt-in Dagger module that config-drives {@link PrincipalAuthorityResolutionConfig} — the Mode-2
 * {@link dev.vertique.security.authz.PrincipalAuthorityResolver} resolution timeout — from the
 * {@code identity.authz} section of the application config (PRD identity-002 §14.3 Phase-2
 * Appendix, ADR-0169).
 *
 * <p>{@code SecurityAuthzModule} declares {@code Optional<PrincipalAuthorityResolutionConfig>} via
 * {@code @BindsOptionalOf}, defaulting to {@link PrincipalAuthorityResolutionConfig#defaults()}
 * when no module binds one — installing this module (or binding {@link
 * PrincipalAuthorityResolutionConfig} programmatically some other way) is how an application
 * overrides the default timeout. Installing this module has no effect unless the application has
 * also bound a {@link dev.vertique.security.authz.PrincipalAuthorityResolver} (the separate Mode-2
 * opt-in {@code SecurityAuthzModule} declares via its own {@code @BindsOptionalOf}); with no
 * resolver bound, the configured timeout is simply unused.
 *
 * <p>Config path: {@code identity.authz} — for example:
 *
 * <pre>{@code
 * identity:
 *   authz:
 *     resolutionTimeoutMs: 5000
 * }</pre>
 *
 * <p>Include this module alongside {@code SecurityAuthzModule} in any Dagger {@code @Component}
 * that should config-drive the Mode-2 resolution timeout instead of using the framework default.
 */
@Module
public abstract class PrincipalAuthorityResolutionConfigModule {

    private PrincipalAuthorityResolutionConfigModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the parsed {@link PrincipalAuthorityResolutionConfig} from the {@code identity.authz}
     * section of the application config.
     *
     * @param config the full application configuration injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed, validated resolution-timeout configuration; never {@code null}
     */
    @Provides
    @Singleton
    static PrincipalAuthorityResolutionConfig principalAuthorityResolutionConfig(
            @VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "identity", "authz"), PrincipalAuthorityResolutionConfig.class);
    }
}
