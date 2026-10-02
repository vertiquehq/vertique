// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.io.InputStream;

/**
 * The example's API documentation page: a {@link RouterMount} at {@value #MOUNT_PATH} that answers
 * {@code GET /apidocs/ui/} with a Redoc page rendering the public document, and nothing else.
 * {@code HEAD /apidocs/ui/} gets the same status and headers with no body.
 *
 * <p>The mount shares the documentation prefix. The documentation mount answers only its exact
 * document URLs and lets every other request under the prefix continue, so this mount, in the
 * default phase, receives the page request. The framework logs the overlap of the two mounts at
 * startup; that warning is expected.
 *
 * <p>The page loads Redoc's standalone bundle, version 2.5.4, from jsDelivr by its exact version and
 * checks it with Subresource Integrity. The SHA-384 digest was computed on 2026-10-02 from two
 * separate downloads of {@link #REDOC_SCRIPT_URL} that agreed. No other third-party asset is
 * referenced, and the page itself is read once from the classpath resource {@value #PAGE_RESOURCE}.
 *
 * <p>The policy allows scripts only from this origin and from exactly the pinned script's full URL,
 * never from the whole CDN origin.
 */
final class ApiDocsUiMount implements RouterMount {

    /** The mount path, under the default documentation prefix. */
    static final String MOUNT_PATH = "/apidocs/ui/*";

    /** The classpath resource holding the page. */
    static final String PAGE_RESOURCE = "apidocs-ui/index.html";

    /** The page's {@code Content-Type}. */
    static final String CONTENT_TYPE = "text/html; charset=utf-8";

    /** The full URL of the pinned Redoc standalone bundle. */
    static final String REDOC_SCRIPT_URL = "https://cdn.jsdelivr.net/npm/redoc@2.5.4/bundles/redoc.standalone.js";

    /** The Subresource Integrity value of {@link #REDOC_SCRIPT_URL}. */
    static final String REDOC_SCRIPT_INTEGRITY =
            "sha384-w447zOpYfw/1Tv/5AK9NfHTlQIqE3RVR6KY62jCyy9zNDgO64cMwGGP1Fj0zJVf5";

    /**
     * The page's content security policy: a restrictive base policy whose {@code script-src} names
     * this origin and exactly {@link #REDOC_SCRIPT_URL}, plus the two additions Redoc 2.5.4 needs,
     * measured in a browser on 2026-10-02. {@code style-src 'unsafe-inline'} is needed because Redoc
     * injects its styles at run time. {@code worker-src blob:} is needed because its search runs in a
     * worker created from a blob URL. Images it would load (a data-URL icon and a logo from another
     * origin) stay blocked without affecting rendering.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self' " + REDOC_SCRIPT_URL
            + "; style-src 'self' 'unsafe-inline'; connect-src 'self'; worker-src blob:; frame-ancestors 'none'";

    /** Creates the mount. */
    ApiDocsUiMount() {}

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    /**
     * Reads the page from the classpath and creates the router that serves it at {@code /}.
     *
     * @param vertx the Vert.x instance
     * @return a future resolving to the router, failed when the page resource is missing
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        return vertx.executeBlocking(ApiDocsUiMount::readPage).map(page -> {
            Router router = Router.router(vertx);
            // One route for both methods, as the documentation mount registers its document routes.
            router.route("/").method(HttpMethod.GET).method(HttpMethod.HEAD).handler(ctx -> serve(ctx, page));
            return router;
        });
    }

    /**
     * Answers a request for the page: the content type, security policy, and length, and the page's
     * bytes unless the method is {@code HEAD}.
     *
     * @param ctx the routing context
     * @param page the page's UTF-8 bytes
     */
    private static void serve(RoutingContext ctx, byte[] page) {
        HttpServerResponse response = ctx.response()
                .putHeader("Content-Type", CONTENT_TYPE)
                .putHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .putHeader("Content-Length", Integer.toString(page.length));
        if (ctx.request().method() == HttpMethod.HEAD) {
            response.end();
        } else {
            response.end(Buffer.buffer(page));
        }
    }

    private static byte[] readPage() throws IOException {
        try (InputStream in = ApiDocsUiMount.class.getClassLoader().getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the page resource " + PAGE_RESOURCE + " is not on the classpath");
            }
            return in.readAllBytes();
        }
    }
}
