// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Installs synthetic operations through the public installer of a composition onto a bare router it
 * owns, and reads back what a refusal leaves behind. Closing it closes the {@link Vertx} it owns.
 */
final class TypedSyntheticInstallProbe implements AutoCloseable {

    private final Vertx vertx = Vertx.vertx();
    private final Router router = Router.router(vertx);

    /**
     * Installs the operation as a {@code GET} route and returns the router's route count afterwards.
     *
     * @param installer the composition's public installer
     * @param path      the route path
     * @param operation the operation to install
     * @return how many routes the router holds afterwards
     */
    int install(SyntheticOperationInstaller installer, String path, SyntheticOperation operation) {
        installer.install(router, path, List.of(HttpMethod.GET), operation, ctx -> ctx.response()
                .end("installed"));
        return routeCount();
    }

    /**
     * Installs the operation, expects the installer to refuse it, and checks the refusal names the
     * operation's origin and left the router without any route.
     *
     * @param installer the composition's public installer
     * @param path      the route path
     * @param operation the operation expected to be refused
     * @return the refusal
     */
    RestConfigurationException refusal(
            SyntheticOperationInstaller installer, String path, SyntheticOperation operation) {
        RestConfigurationException refusal = assertThrows(
                RestConfigurationException.class,
                () -> installer.install(router, path, List.of(HttpMethod.GET), operation, ctx -> ctx.response()
                        .end("must never be served")),
                "the installer must refuse " + operation.operationId());
        assertTrue(
                refusal.getMessage().startsWith(operation.origin()),
                "the refusal must start with the origin but was: " + refusal.getMessage());
        assertEquals(0, routeCount(), "a refused operation must leave no route on the router");
        return refusal;
    }

    /**
     * Returns the types of the policy violations a refusal carries as its cause.
     *
     * @param refusal the refusal
     * @return the violation types of its {@link SecurityPolicyViolationException} cause
     */
    static List<SecurityPolicyViolation.ViolationType> policyViolationTypes(RestConfigurationException refusal) {
        SecurityPolicyViolationException cause = org.junit.jupiter.api.Assertions.assertInstanceOf(
                SecurityPolicyViolationException.class, refusal.getCause());
        return cause.violations().stream().map(SecurityPolicyViolation::type).toList();
    }

    /**
     * Returns the types of the route violations a refusal carries as its cause.
     *
     * @param refusal the refusal
     * @return the violation types of its {@link RouteRegistrationException} cause
     */
    static List<RouteRegistrationViolation.ViolationType> routeViolationTypes(RestConfigurationException refusal) {
        RouteRegistrationException cause =
                org.junit.jupiter.api.Assertions.assertInstanceOf(RouteRegistrationException.class, refusal.getCause());
        return cause.violations().stream().map(RouteRegistrationViolation::type).toList();
    }

    private int routeCount() {
        return router.getRoutes().size();
    }

    @Override
    public void close() throws Exception {
        CleanupFailures cleanup = new CleanupFailures();
        cleanup.await(vertx::close, 15, TimeUnit.SECONDS);
        cleanup.rethrowIfAny();
    }
}
