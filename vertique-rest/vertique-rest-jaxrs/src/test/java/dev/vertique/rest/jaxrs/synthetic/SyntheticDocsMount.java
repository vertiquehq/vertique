// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/**
 * The IT's {@code SYSTEM_FIRST} test mount at {@code /apidocs/*}: installs three synthetic
 * operations through {@link SyntheticOperations} — a role-protected document, an authenticated-
 * only document, and a role-protected document whose terminal throws — each answering {@code GET}
 * and {@code HEAD}.
 */
final class SyntheticDocsMount implements RouterMount {

    static final String MOUNT_PATH = "/apidocs/*";
    static final String ORIGIN = "@ApiDocs on application 'management'";
    static final String SCHEME = "bearerAuth";

    static final String MANAGEMENT_OPERATION_ID = "apidocs:management:json";
    static final String MANAGEMENT_PATH = "/management/openapi.json";
    static final String MANAGEMENT_BODY = "management-document-bytes";

    static final String AUTHENTICATED_OPERATION_ID = "apidocs:authenticated:json";
    static final String AUTHENTICATED_PATH = "/authenticated/openapi.json";
    static final String AUTHENTICATED_BODY = "authenticated-document-bytes";

    static final String BROKEN_OPERATION_ID = "apidocs:broken:json";
    static final String BROKEN_PATH = "/broken/openapi.json";

    private final SyntheticOperations operations;
    private final TraceRecorder trace;

    /**
     * Creates the mount.
     *
     * @param operations the installer the test component's {@code @Binds} provides
     * @param trace      the shared trace recorder
     */
    SyntheticDocsMount(SyntheticOperations operations, TraceRecorder trace) {
        this.operations = operations;
        this.trace = trace;
    }

    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);

        operations.install(
                router,
                MANAGEMENT_PATH,
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                SyntheticOperation.withRoles(ORIGIN, MANAGEMENT_OPERATION_ID, SCHEME, "management", List.of("admin")),
                terminal(MANAGEMENT_BODY));

        operations.install(
                router,
                AUTHENTICATED_PATH,
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                SyntheticOperation.authenticated(ORIGIN, AUTHENTICATED_OPERATION_ID, SCHEME, "authenticated"),
                terminal(AUTHENTICATED_BODY));

        operations.install(
                router,
                BROKEN_PATH,
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                SyntheticOperation.withRoles(ORIGIN, BROKEN_OPERATION_ID, SCHEME, "broken", List.of("admin")),
                ctx -> {
                    throw new RuntimeException("broken terminal");
                });

        return Future.succeededFuture(router);
    }

    private Handler<RoutingContext> terminal(String body) {
        return ctx -> {
            trace.record(ctx, "terminal");
            ctx.response().end(Buffer.buffer(body));
        };
    }
}
