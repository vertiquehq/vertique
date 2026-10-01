// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.MountCustomizer;

/**
 * Binds the mount-scoped controls of the uncovered-control warning proof: {@link MgmtOnlyCustomizer},
 * {@link EveryMountCustomizer}, {@link ApiAllowlistMiddleware}, {@link AuditHook},
 * {@link TenantInterceptor}, and {@link LoggingOnlyInterceptor}. The framework's content-type
 * middleware is bound by the REST module itself.
 */
@Module
public final class WarningControls {

    private WarningControls() {}

    /**
     * Contributes the customizer matching only the management mount.
     *
     * @return the customizer
     */
    @Provides
    @IntoSet
    static MountCustomizer mgmtOnlyCustomizer() {
        return new MgmtOnlyCustomizer();
    }

    /**
     * Contributes the customizer matching every mount.
     *
     * @return the customizer
     */
    @Provides
    @IntoSet
    static MountCustomizer everyMountCustomizer() {
        return new EveryMountCustomizer();
    }

    /**
     * Contributes the {@code API}-scoped middleware.
     *
     * @return the middleware
     */
    @Provides
    @IntoSet
    static Middleware apiAllowlistMiddleware() {
        return new ApiAllowlistMiddleware();
    }

    /**
     * Contributes the router lifecycle hook.
     *
     * @return the hook
     */
    @Provides
    @IntoSet
    static RouterLifecycleHook auditHook() {
        return new AuditHook();
    }

    /**
     * Contributes the interceptor overriding {@code beforeRequest}.
     *
     * @return the interceptor
     */
    @Provides
    @IntoSet
    static RequestInterceptor tenantInterceptor() {
        return new TenantInterceptor();
    }

    /**
     * Contributes the interceptor overriding only {@code onRequest}.
     *
     * @return the interceptor
     */
    @Provides
    @IntoSet
    static RequestInterceptor loggingOnlyInterceptor() {
        return new LoggingOnlyInterceptor();
    }
}
