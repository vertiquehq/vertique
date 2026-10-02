// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * A plain router mount at {@value #MOUNT_PATH}, the documentation prefix, in the default phase, so it
 * is mounted after the documentation mount and receives every request the documentation mount lets
 * continue. Every invocation is counted in the component's {@link Observations}:
 *
 * <ul>
 *   <li>{@code GET} {@value #MANAGEMENT_ROUTE}, the protected document's own URL under the prefix,
 *       answers {@code 200} {@value #BODY};
 *   <li>{@code GET} {@value #BOOM_ROUTE} fails with {@code 500};
 *   <li>a catch-all route, which also records whether {@code ctx.user()} was set, answers {@code 404}
 *       {@value #BODY};
 *   <li>a failure handler answers {@code 500} {@value #BODY}.
 * </ul>
 */
public final class LaterDocsPrefixMount implements RouterMount {

    /** The mount path. */
    public static final String MOUNT_PATH = "/apidocs/*";

    /** The route at the protected document's URL, relative to the mount. */
    public static final String MANAGEMENT_ROUTE = "/management/openapi.json";

    /** The route that always fails, relative to the mount. */
    public static final String BOOM_ROUTE = "/boom";

    /** The body of every response this mount writes. */
    public static final String BODY = "later-mount";

    private final Observations observations;

    /**
     * Creates the mount.
     *
     * @param observations the component's observation hub
     */
    public LaterDocsPrefixMount(Observations observations) {
        this.observations = observations;
    }

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        router.get(MANAGEMENT_ROUTE).handler(ctx -> {
            observations.laterMountRouteHit();
            observations.laterMountManagementRouteHit();
            ctx.response().setStatusCode(200).end(BODY);
        });
        router.get(BOOM_ROUTE).handler(ctx -> {
            observations.laterMountRouteHit();
            ctx.fail(500, new IllegalStateException("the later mount's route failed on purpose"));
        });
        router.route().handler(ctx -> {
            observations.laterMountRouteHit();
            observations.laterMountCatchAllHit(ctx.user() != null);
            ctx.response().setStatusCode(404).end(BODY);
        });
        router.route().failureHandler(ctx -> {
            observations.laterMountFailureHandlerHit();
            if (!ctx.response().ended()) {
                ctx.response().setStatusCode(500).end(BODY);
            }
        });
        return Future.succeededFuture(router);
    }
}
