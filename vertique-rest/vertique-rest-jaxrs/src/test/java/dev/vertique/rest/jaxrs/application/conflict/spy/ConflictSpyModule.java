// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.spy;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.router.RouterMount;

/**
 * TP-004 (T004) fixture module: contributes {@link CountingRouterMount} and
 * {@link CountingRouterLifecycleHook} into every {@code JaxRsApplicationMountConflictIT}
 * composition, through {@code RestCoreModule}'s existing {@code @Multibinds Set<RouterMount>} and
 * {@code Set<RouterLifecycleHook>} declarations, so both spies are available without any
 * production change.
 */
@Module
public final class ConflictSpyModule {

    private ConflictSpyModule() {}

    /**
     * Contributes the non-JAX-RS counting mount.
     *
     * @return a fresh {@link CountingRouterMount}
     */
    @Provides
    @IntoSet
    static RouterMount countingRouterMount() {
        return new CountingRouterMount();
    }

    /**
     * Contributes the JAX-RS router-creation counting hook.
     *
     * @return a fresh {@link CountingRouterLifecycleHook}
     */
    @Provides
    @IntoSet
    static RouterLifecycleHook countingRouterLifecycleHook() {
        return new CountingRouterLifecycleHook();
    }
}
