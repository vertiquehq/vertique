// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plain {@link RouterMount} at {@value #MOUNT_PATH} in phase {@code SYSTEM_FIRST} with priority
 * {@link Integer#MIN_VALUE} that counts its {@code createRouter} calls and registers no route. It is
 * the first mount a composition builds, so a count of {@code 0} shows that no router was created.
 */
public final class RouterSpy implements RouterMount {

    /** The spy's mount path. */
    public static final String MOUNT_PATH = "/zz-spy/*";

    private final AtomicInteger createRouterCalls = new AtomicInteger();

    /** Creates a spy with no recorded call. */
    public RouterSpy() {}

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        createRouterCalls.incrementAndGet();
        return Future.succeededFuture(Router.router(vertx));
    }

    /**
     * Returns the number of {@code createRouter} calls since creation or the last {@link #reset()}.
     *
     * @return the call count
     */
    public int createRouterCalls() {
        return createRouterCalls.get();
    }

    /** Sets the call count back to {@code 0}. */
    public void reset() {
        createRouterCalls.set(0);
    }

    /** Binds one component-scoped {@link RouterSpy} into {@code Set<RouterMount>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's spy.
         *
         * @return a new spy
         */
        @Provides
        @Singleton
        static RouterSpy routerSpy() {
            return new RouterSpy();
        }

        /**
         * Contributes the spy.
         *
         * @param spy the component's spy
         * @return {@code spy}
         */
        @Provides
        @IntoSet
        static RouterMount asRouterMount(RouterSpy spy) {
            return spy;
        }
    }
}
