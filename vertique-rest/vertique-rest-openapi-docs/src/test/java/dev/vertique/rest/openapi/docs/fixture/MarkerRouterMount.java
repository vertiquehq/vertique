// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * A {@link RouterMount} at {@code /*} that answers every request it reaches with {@code 200}, an
 * empty body, and the header {@value #HEADER} carrying its marker value, so a response shows which
 * mount answered it.
 */
public final class MarkerRouterMount implements RouterMount {

    /** The header every marker response carries. */
    public static final String HEADER = "X-Marker";

    /** The marker value of the mount placed after every other mount. */
    public static final String LAST = "last";

    /** The marker value of the application-phase mount placed before every other application mount. */
    public static final String EARLY = "early";

    private final ExtensionPhase phase;
    private final int priority;
    private final String marker;

    /**
     * Creates a marker mount.
     *
     * @param phase    the mount's phase
     * @param priority the mount's priority within its phase
     * @param marker   the value of the {@value #HEADER} header
     */
    public MarkerRouterMount(ExtensionPhase phase, int priority, String marker) {
        this.phase = phase;
        this.priority = priority;
        this.marker = marker;
    }

    @Override
    public ExtensionPhase phase() {
        return phase;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public String orderKey() {
        return MarkerRouterMount.class.getName() + ":" + marker;
    }

    @Override
    public String mountPath() {
        return "/*";
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        router.route().handler(ctx -> ctx.response()
                .putHeader(HEADER, marker)
                .setStatusCode(200)
                .end());
        return Future.succeededFuture(router);
    }

    /** Contributes a marker mount in the {@code SYSTEM_LAST} phase, mounted after every other mount. */
    @Module
    public static final class Last {

        private Last() {}

        /**
         * Provides the last marker mount.
         *
         * @return the mount, marked {@value MarkerRouterMount#LAST}
         */
        @Provides
        @IntoSet
        static RouterMount lastMarkerMount() {
            return new MarkerRouterMount(ExtensionPhase.SYSTEM_LAST, Integer.MAX_VALUE, LAST);
        }
    }

    /**
     * Contributes a marker mount in the {@code APPLICATION} phase with priority
     * {@link Integer#MIN_VALUE}, mounted before every other application-phase mount.
     */
    @Module
    public static final class Early {

        private Early() {}

        /**
         * Provides the early marker mount.
         *
         * @return the mount, marked {@value MarkerRouterMount#EARLY}
         */
        @Provides
        @IntoSet
        static RouterMount earlyMarkerMount() {
            return new MarkerRouterMount(ExtensionPhase.APPLICATION, Integer.MIN_VALUE, EARLY);
        }
    }
}
