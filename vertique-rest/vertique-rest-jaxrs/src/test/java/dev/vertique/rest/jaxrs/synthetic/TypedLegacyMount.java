// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/**
 * The mount installing the two legacy synthetic operations, a role-restricted one and an
 * authenticated-only one, behind the bearer scheme of a real security composition. It is the
 * unchanged-behavior control of the typed policy operations.
 */
final class TypedLegacyMount implements RouterMount {

    static final String MOUNT_PATH = "/legacy/*";
    static final String ORIGIN = "legacy synthetic operation";
    static final String ROLES_OPERATION_ID = "legacy:roles";
    static final String ROLES_PATH = "/roles";
    static final String AUTHENTICATED_OPERATION_ID = "legacy:authenticated";
    static final String AUTHENTICATED_PATH = "/authenticated";

    private final SyntheticOperationInstaller operations;
    private final TypedSyntheticObservations observations;

    TypedLegacyMount(SyntheticOperationInstaller operations, TypedSyntheticObservations observations) {
        this.operations = operations;
        this.observations = observations;
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
                ROLES_PATH,
                List.of(HttpMethod.GET),
                SyntheticOperation.withRoles(
                        ORIGIN,
                        ROLES_OPERATION_ID,
                        TypedBearerAuthentication.SCHEME,
                        "legacy",
                        List.of(TypedPolicies.ROLE)),
                terminal(ROLES_OPERATION_ID));
        operations.install(
                router,
                AUTHENTICATED_PATH,
                List.of(HttpMethod.GET),
                SyntheticOperation.authenticated(
                        ORIGIN, AUTHENTICATED_OPERATION_ID, TypedBearerAuthentication.SCHEME, "legacy"),
                terminal(AUTHENTICATED_OPERATION_ID));
        return Future.succeededFuture(router);
    }

    private Handler<RoutingContext> terminal(String operationId) {
        return ctx -> {
            observations.recordTerminal(operationId);
            ctx.response().end("ok:" + operationId);
        };
    }
}
