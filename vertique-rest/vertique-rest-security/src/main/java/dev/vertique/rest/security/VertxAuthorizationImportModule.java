// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.dagger.ResilienceModule;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Opt-in Dagger module that activates the import of Vert.x {@link AuthorizationProvider} grants into
 * the framework's {@link dev.vertique.security.authz.AuthorizationClaims}.
 *
 * <p>Without this module the {@link AuthorizationProvider} multibinding set declared by
 * {@link AuthModule} is inert: providers may be contributed, but none is ever consulted and
 * authorization decisions are made purely from the claims the identity pipeline resolved. Including
 * this module satisfies {@link AuthModule#optionalVertxAuthorizationImporter()}, so
 * {@link IdentityResolutionMiddleware} runs the provider chain on every authenticated request and
 * merges the resulting authorities into the bound claims.
 *
 * <p><strong>Wiring.</strong> Include it alongside {@link AuthModule} and {@link SecurityModule} (or
 * a JWT authentication module such as {@code JwtAuthModule}) in the application component:
 * <pre>{@code
 * @Singleton
 * @Component(modules = {
 *     AuthModule.class,
 *     SecurityModule.class,
 *     VertxAuthorizationImportModule.class,
 *     MyAuthorizationProviderModule.class // contributes @Provides @IntoSet AuthorizationProvider
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p><strong>Default exclusion.</strong> The importer provided here is always built with the safe
 * constructor, so the Vert.x
 * {@value VertxAuthorizationImporter#EXCLUDED_JWT_CLAIMS_PROVIDER_ID} bucket is excluded: its
 * scope→permission projection is lossy, and the JWT principal already reaches
 * {@link dev.vertique.security.authz.AuthorizationClaims} with full kind fidelity (a {@code scope}
 * claim stays a {@code SCOPE} authority) through {@link SecurityClaimMapper}. See the
 * {@link VertxAuthorizationImporter} class javadoc for the importer's full execution contract —
 * sequential invocation, all-or-nothing publication, and fail-closed authorization mapping.
 *
 * @see VertxAuthorizationImporter
 * @see AuthModule
 * @see IdentityResolutionMiddleware
 */
@Slf4j
@Module(includes = ResilienceModule.class)
public abstract class VertxAuthorizationImportModule {

    /**
     * Declares the optional {@link AuthorizationImportConfig} binding, defaulting to {@link
     * AuthorizationImportConfig#defaults()} when absent. {@link AuthorizationImportConfigModule} is the
     * opt-in companion that config-drives the provider deadline from {@code security.authz.importTimeoutMs}.
     *
     * @return the optional configuration
     */
    @BindsOptionalOf
    abstract AuthorizationImportConfig optionalAuthorizationImportConfig();

    /**
     * Provides the {@link VertxAuthorizationImporter} over the contributed Vert.x authorization
     * providers, using the safe constructor that excludes the
     * {@value VertxAuthorizationImporter#EXCLUDED_JWT_CLAIMS_PROVIDER_ID} bucket.
     *
     * @param providers the registered Vert.x authorization providers contributed via the
     *                  {@link AuthModule#authorizationProviders()} multibinding; never {@code null}
     * @param config    the optional operator-configured provider deadline; empty defaults to
     *                  {@link AuthorizationImportConfig#defaults()}
     * @param resilience the application's resilience runtime, which bounds each provider invocation
     * @return the importer consulted by {@link IdentityResolutionMiddleware}
     * @throws IllegalStateException if any provider exposes a {@code null} or blank id, or if two
     *                               providers share the same id
     */
    @Provides
    @Singleton
    static VertxAuthorizationImporter vertxAuthorizationImporter(
            Set<AuthorizationProvider> providers, Optional<AuthorizationImportConfig> config, Resilience resilience) {
        AuthorizationImportConfig effective = config.orElseGet(AuthorizationImportConfig::defaults);
        log.info(
                "security.authz.importTimeoutMs={} ({})",
                effective.importTimeoutMs(),
                config.isPresent() ? "configured" : "default");
        return new VertxAuthorizationImporter(providers, resilience, effective);
    }
}
