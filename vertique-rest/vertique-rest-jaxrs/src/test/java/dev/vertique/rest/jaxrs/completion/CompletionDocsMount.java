// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.completion;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import java.util.List;

/**
 * The fixture's {@code SYSTEM_FIRST} mount at {@code /apidocs/*}: installs one role-protected
 * synthetic operation, {@code apidocs:management:json} of application {@code management}, at
 * {@code /management/openapi.json} through {@link SyntheticOperationInstaller}, answering {@code GET} and
 * {@code HEAD}. Its terminal writes a fixed body.
 */
final class CompletionDocsMount implements RouterMount {

    static final String MOUNT_PATH = "/apidocs/*";
    static final String ORIGIN = "origin";
    static final String OPERATION_ID = "apidocs:management:json";
    static final String APPLICATION_NAME = "management";
    static final String DOCUMENT_PATH = "/management/openapi.json";
    static final String DOCUMENT_BODY = "management-document-bytes";

    private final SyntheticOperationInstaller operations;

    /**
     * Creates the mount.
     *
     * @param operations the installer the test component provides
     */
    CompletionDocsMount(SyntheticOperationInstaller operations) {
        this.operations = operations;
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
                DOCUMENT_PATH,
                List.of(HttpMethod.GET, HttpMethod.HEAD),
                SyntheticOperation.withRoles(
                        ORIGIN, OPERATION_ID, StubAuthentication.SCHEME, APPLICATION_NAME, List.of("admin")),
                ctx -> ctx.response().end(Buffer.buffer(DOCUMENT_BODY)));
        return Future.succeededFuture(router);
    }
}
