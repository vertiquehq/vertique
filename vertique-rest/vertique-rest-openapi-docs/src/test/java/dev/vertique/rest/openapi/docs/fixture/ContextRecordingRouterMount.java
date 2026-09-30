// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@code SYSTEM_LAST} {@link RouterMount} at {@value #MOUNT_PATH} that records
 * {@link Vertx#currentContext()} each time {@code createRouter} runs and registers no route. One
 * instance is shared by every composition of its component, so it shows on which context each
 * composition reached its last mount.
 */
public final class ContextRecordingRouterMount implements RouterMount {

    /** The mount's path. */
    public static final String MOUNT_PATH = "/zz-last/*";

    private final List<Context> contexts = new CopyOnWriteArrayList<>();

    /** Creates a mount with no recorded context. */
    public ContextRecordingRouterMount() {}

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_LAST;
    }

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        contexts.add(Vertx.currentContext());
        return Future.succeededFuture(Router.router(vertx));
    }

    /**
     * Returns the context of every {@code createRouter} call, in call order; an entry is
     * {@code null} when a call ran on no Vert.x context.
     *
     * @return an immutable snapshot of the recorded contexts
     */
    public List<Context> contexts() {
        return List.copyOf(contexts);
    }

    /** Binds one component-scoped {@link ContextRecordingRouterMount} into {@code Set<RouterMount>}. */
    @Module
    public static final class Binding {

        private Binding() {}

        /**
         * Provides the component's context-recording mount.
         *
         * @return a new mount
         */
        @Provides
        @Singleton
        static ContextRecordingRouterMount contextRecordingRouterMount() {
            return new ContextRecordingRouterMount();
        }

        /**
         * Contributes the context-recording mount.
         *
         * @param mount the component's context-recording mount
         * @return {@code mount}
         */
        @Provides
        @IntoSet
        static RouterMount asRouterMount(ContextRecordingRouterMount mount) {
            return mount;
        }
    }
}
