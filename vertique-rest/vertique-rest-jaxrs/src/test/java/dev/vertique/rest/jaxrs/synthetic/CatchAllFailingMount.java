// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The IT's catch-all mount at {@code /*}, mounted last ({@link ExtensionPhase#SYSTEM_LAST}): fails
 * every request it receives and counts every failure its own failure handler observed. A request
 * that reaches this mount matched neither the {@code SYSTEM_FIRST} synthetic mount, the twin
 * mount, nor the later {@code /apidocs/*} mount — the live control for an unmatched path such as
 * {@code /nowhere}.
 */
public final class CatchAllFailingMount implements RouterMount {

    private final AtomicInteger failureCount = new AtomicInteger();

    @Override
    public String mountPath() {
        return "/*";
    }

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_LAST;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        router.route().handler(ctx -> ctx.fail(404)).failureHandler(ctx -> {
            failureCount.incrementAndGet();
            if (!ctx.response().ended()) {
                ctx.response().setStatusCode(404).end();
            }
        });
        return Future.succeededFuture(router);
    }

    /**
     * Returns how many failures this mount's failure handler observed.
     *
     * @return the failure count
     */
    public int failureCount() {
        return failureCount.get();
    }
}
