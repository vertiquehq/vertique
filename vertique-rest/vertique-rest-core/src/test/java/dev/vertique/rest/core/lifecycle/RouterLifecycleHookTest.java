// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import dev.vertique.rest.core.routing.RouterSetup;
import io.vertx.ext.web.Router;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RouterLifecycleHook#beforeAuthSetup} and {@link RouterLifecycleHook#afterAuthSetup}
 * accept the neutral {@link RouterSetup} rather than the Vert.x OpenAPI {@code RouterBuilder} (FR-022),
 * while {@link RouterLifecycleHook#afterRouterCreated} continues to receive the Vert.x {@link Router}
 * unchanged.
 *
 * <p>The test is a compile-time contract check: a hook implementation that overrides
 * {@code beforeAuthSetup(RouterSetup)} / {@code afterAuthSetup(RouterSetup)} must satisfy the
 * interface, proving {@code RouterBuilder} is no longer part of the SPI surface.
 */
class RouterLifecycleHookTest {

    /**
     * {@link RouterLifecycleHook} implementation overriding the auth-setup callbacks with the neutral
     * {@link RouterSetup} type, recording the last received setup and router for verification.
     */
    private static final class NeutralHook implements RouterLifecycleHook {

        final AtomicReference<RouterSetup> beforeSetup = new AtomicReference<>();
        final AtomicReference<RouterSetup> afterSetup = new AtomicReference<>();
        final AtomicReference<Router> created = new AtomicReference<>();

        @Override
        public void beforeAuthSetup(RouterSetup setup) {
            beforeSetup.set(setup);
        }

        @Override
        public void afterAuthSetup(RouterSetup setup) {
            afterSetup.set(setup);
        }

        @Override
        public void afterRouterCreated(Router router) {
            created.set(router);
        }
    }

    @Test
    @DisplayName("beforeAuthSetup/afterAuthSetup accept RouterSetup; afterRouterCreated keeps Router")
    void authSetupCallbacksAcceptRouterSetup() {
        NeutralHook hook = new NeutralHook();
        RouterSetup setup = mock(RouterSetup.class);
        Router router = mock(Router.class);

        hook.beforeAuthSetup(setup);
        hook.afterAuthSetup(setup);
        hook.afterRouterCreated(router);

        assertSame(setup, hook.beforeSetup.get(), "beforeAuthSetup must receive the RouterSetup");
        assertSame(setup, hook.afterSetup.get(), "afterAuthSetup must receive the RouterSetup");
        assertSame(router, hook.created.get(), "afterRouterCreated must still receive the Router");
    }
}
