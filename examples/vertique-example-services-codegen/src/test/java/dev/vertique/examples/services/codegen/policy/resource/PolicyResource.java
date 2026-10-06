// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.resource;

import dev.vertique.examples.services.codegen.policy.PolicyFixtures;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.AdminPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.AuthenticatedPolicy;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.PermitPolicy;
import dev.vertique.examples.services.codegen.policy.service.PolicyHandlerService;
import dev.vertique.examples.services.codegen.policy.service.PolicyService;
import dev.vertique.security.authz.RequiresPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.function.Function;

/**
 * REST entry points of the typed access-policy proof. Each route carries one outer policy and
 * forwards the request to the generated client of the chosen service contract, so a test controls
 * the outer decision, the service operation and the caller independently.
 *
 * <p>The path is {@code /policy/{outer}/{pattern}/{operation}}: {@code outer} is {@code public},
 * {@code authenticated} or {@code admin}; {@code pattern} is {@code direct} or {@code handler};
 * {@code operation} is one of the operation names of the service contracts.
 */
@Path("/policy")
@Produces(MediaType.TEXT_PLAIN)
public class PolicyResource {

    private final Map<String, Map<String, Function<String, Future<String>>>> clients;

    /**
     * Creates the resource.
     *
     * @param direct  the generated client of the direct-implementation contract
     * @param handler the generated client of the handler contract
     */
    @Inject
    public PolicyResource(PolicyService direct, PolicyHandlerService handler) {
        this.clients = Map.of(
                "direct", PolicyFixtures.operations(direct),
                "handler", PolicyFixtures.operations(handler));
    }

    /**
     * Forwards the request behind a public outer policy.
     *
     * @param pattern    the service implementation pattern
     * @param operation  the service operation
     * @param resourceId the resource the caller acts on
     * @return the service result
     */
    @GET
    @Path("/public/{pattern}/{operation}")
    @RequiresPolicy(PermitPolicy.class)
    public Future<String> viaPublic(
            @PathParam("pattern") String pattern,
            @PathParam("operation") String operation,
            @QueryParam("id") String resourceId) {
        return forward(pattern, operation, resourceId);
    }

    /**
     * Forwards the request behind an authentication-only outer policy.
     *
     * @param pattern    the service implementation pattern
     * @param operation  the service operation
     * @param resourceId the resource the caller acts on
     * @return the service result
     */
    @GET
    @Path("/authenticated/{pattern}/{operation}")
    @RequiresPolicy(AuthenticatedPolicy.class)
    @Operation(security = @SecurityRequirement(name = "bearerAuth"))
    public Future<String> viaAuthenticated(
            @PathParam("pattern") String pattern,
            @PathParam("operation") String operation,
            @QueryParam("id") String resourceId) {
        return forward(pattern, operation, resourceId);
    }

    /**
     * Forwards the request behind an admin-role outer policy.
     *
     * @param pattern    the service implementation pattern
     * @param operation  the service operation
     * @param resourceId the resource the caller acts on
     * @return the service result
     */
    @GET
    @Path("/admin/{pattern}/{operation}")
    @RequiresPolicy(AdminPolicy.class)
    @Operation(security = @SecurityRequirement(name = "bearerAuth"))
    public Future<String> viaAdmin(
            @PathParam("pattern") String pattern,
            @PathParam("operation") String operation,
            @QueryParam("id") String resourceId) {
        return forward(pattern, operation, resourceId);
    }

    private Future<String> forward(String pattern, String operation, String resourceId) {
        Function<String, Future<String>> call =
                clients.getOrDefault(pattern, Map.of()).get(operation);
        if (call == null) {
            return Future.failedFuture(new NotFoundException("no operation " + pattern + "/" + operation));
        }
        return call.apply(resourceId);
    }
}
