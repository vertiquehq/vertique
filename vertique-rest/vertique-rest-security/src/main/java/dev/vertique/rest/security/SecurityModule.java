// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import jakarta.inject.Singleton;

/**
 * Dagger module providing security runtime bindings.
 *
 * <p>Include this module in your application's Dagger component to enable
 * {@link SecurityRuntime} backed by the unified {@link dev.vertique.core.context.ContextHolder}
 * storage via {@link HolderBackedSecurityRuntime}.
 *
 * <p>Example usage in a Dagger component:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, SecurityModule.class, ...})
 * public interface AppComponent {
 *     HttpVerticle httpVerticle();
 * }
 * }</pre>
 */
@Module
public abstract class SecurityModule {

    /**
     * Binds the {@link SecurityRuntime} to {@link HolderBackedSecurityRuntime}, which stores the
     * current {@link dev.vertique.security.SecurityContext} in the unified
     * {@link dev.vertique.core.context.ContextHolder} per-request map.
     *
     * @param impl the {@link ContextHolder}-backed implementation
     * @return the security runtime service
     */
    @Binds
    @Singleton
    abstract SecurityRuntime securityRuntime(HolderBackedSecurityRuntime impl);

    /**
     * Provides the JAX-RS {@link jakarta.ws.rs.core.SecurityContext} factory consumed by
     * {@link HolderBackedSecurityRuntime#toJaxRs(dev.vertique.security.SecurityContext, boolean)}.
     * Lives here (not in {@code AuthModule}) so {@link SecurityModule} is self-contained: an
     * application that includes {@code SecurityModule} alone has a complete Dagger graph for the
     * runtime binding without also pulling in {@code AuthModule}.
     *
     * @return the JAX-RS SecurityContext factory
     */
    @Provides
    static JaxRsSecurityContextFactory jaxRsSecurityContextFactory() {
        return JaxRsSecurityContext::new;
    }

    /**
     * Declares {@link IdentitySnapshotCapture} as an optional binding so
     * {@link IdentityResolutionMiddleware} can inject {@code Optional<IdentitySnapshotCapture>}
     * without requiring {@code IdentitySnapshotCarriageModule} (identity-snapshot durable carriage)
     * to be installed.
     *
     * <p>When carriage is installed, its {@code @Provides IdentitySnapshotCapture} satisfies the
     * optional and the middleware captures an identity snapshot at ingress; when absent the optional
     * is empty and the middleware pays nothing on the REST hot path (PRD-ID-002 §15 A3). Mirrors the
     * {@code @BindsOptionalOf IdentitySnapshotDegradationPolicy} declaration in
     * {@code DispatchModule}.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract IdentitySnapshotCapture identitySnapshotCapture();
}
