// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * An application-owned documentation UI: a plain {@link RouterMount} at {@value #MOUNT_PATH} with
 * the default phase, priority, and meta, answering {@code GET /} with an HTML marker page under a
 * restrictive content security policy. It shares the documentation prefix, so it is reached only
 * when the documentation mount lets the request fall through. {@link FallThroughBindings}
 * contributes it.
 */
public final class DocsUiMount implements RouterMount {

    /** The UI mount's path, under the default documentation prefix. */
    public static final String MOUNT_PATH = "/apidocs/ui/*";

    /** The page's {@code Content-Type}. */
    public static final String CONTENT_TYPE = "text/html; charset=utf-8";

    /** The page's {@code Content-Security-Policy}. */
    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'";

    /** The page body, which only this mount sends. */
    public static final String MARKER_BODY = "<!doctype html><title>docs-ui-marker</title>";

    /** Creates the UI mount. */
    public DocsUiMount() {}

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        router.get("/").handler(ctx -> ctx.response()
                .putHeader("Content-Type", CONTENT_TYPE)
                .putHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .end(MARKER_BODY));
        return Future.succeededFuture(router);
    }
}
