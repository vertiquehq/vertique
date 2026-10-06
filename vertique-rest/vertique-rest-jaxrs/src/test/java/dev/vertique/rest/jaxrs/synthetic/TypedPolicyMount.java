// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.security.authz.AccessPolicy;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

/**
 * The mount installing one synthetic operation per fixture policy at {@code /typed/<name>}, each
 * through the public installer of the supported composition. Every terminal handler counts its runs
 * in the shared observations, so a denied request can be shown never to have reached it.
 */
final class TypedPolicyMount implements RouterMount {

    static final String MOUNT_PATH = "/typed/*";
    static final String ORIGIN = "typed synthetic operation";
    static final String APPLICATION = "typed";

    private final SyntheticOperationInstaller operations;
    private final TypedSyntheticObservations observations;

    TypedPolicyMount(SyntheticOperationInstaller operations, TypedSyntheticObservations observations) {
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
        // A public operation names no scheme; every other operation names the bearer scheme.
        install(router, "public", TypedPolicies.Public.class, "");
        install(router, "deny", TypedPolicies.Deny.class, TypedBearerAuthentication.SCHEME);
        install(router, "authenticated", TypedPolicies.Authenticated.class, TypedBearerAuthentication.SCHEME);
        install(router, "roles", TypedPolicies.Roles.class, TypedBearerAuthentication.SCHEME);
        install(router, "scopes", TypedPolicies.Scopes.class, TypedBearerAuthentication.SCHEME);
        install(router, "action", TypedPolicies.Action.class, TypedBearerAuthentication.SCHEME);
        install(router, "roles-action", TypedPolicies.RolesAndAction.class, TypedBearerAuthentication.SCHEME);
        install(router, "combined", TypedPolicies.Combined.class, TypedBearerAuthentication.SCHEME);
        return Future.succeededFuture(router);
    }

    private void install(Router router, String name, Class<? extends AccessPolicy> policy, String schemeName) {
        String operationId = operationId(name);
        operations.install(
                router,
                "/" + name,
                List.of(HttpMethod.GET),
                SyntheticOperation.withPolicy(ORIGIN, operationId, schemeName, APPLICATION, policy),
                terminal(operationId));
    }

    static String operationId(String name) {
        return "typed:" + name;
    }

    private Handler<RoutingContext> terminal(String operationId) {
        return ctx -> {
            observations.recordTerminal(operationId);
            ctx.response().end("ok:" + operationId);
        };
    }
}
