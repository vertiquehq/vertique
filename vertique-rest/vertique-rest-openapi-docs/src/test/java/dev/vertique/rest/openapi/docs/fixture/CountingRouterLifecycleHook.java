// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.routing.RouterSetup;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RouterLifecycleHook} that counts its {@code beforeAuthSetup} calls. A JAX-RS router runs
 * that callback while it is built, so a count of {@code 0} shows that no JAX-RS router was built.
 */
public final class CountingRouterLifecycleHook implements RouterLifecycleHook {

    private final AtomicInteger beforeAuthSetupCalls = new AtomicInteger();

    /** Creates a hook with no recorded call. */
    public CountingRouterLifecycleHook() {}

    @Override
    public void beforeAuthSetup(RouterSetup setup) {
        beforeAuthSetupCalls.incrementAndGet();
    }

    /**
     * Returns the number of {@code beforeAuthSetup} calls.
     *
     * @return the call count
     */
    public int beforeAuthSetupCalls() {
        return beforeAuthSetupCalls.get();
    }

    /** Binds one component-scoped {@link CountingRouterLifecycleHook} into {@code Set<RouterLifecycleHook>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's counting hook.
         *
         * @return a new hook
         */
        @Provides
        @Singleton
        static CountingRouterLifecycleHook countingRouterLifecycleHook() {
            return new CountingRouterLifecycleHook();
        }

        /**
         * Contributes the counting hook.
         *
         * @param hook the component's counting hook
         * @return {@code hook}
         */
        @Provides
        @IntoSet
        static RouterLifecycleHook asRouterLifecycleHook(CountingRouterLifecycleHook hook) {
            return hook;
        }
    }
}
