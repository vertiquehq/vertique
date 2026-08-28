// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient.client;

import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.rest.client.ExpectedStatus;
import dev.vertique.rest.client.RestClient;
import io.vertx.core.Future;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Optional;

/**
 * Declarative REST client interface for the user service API.
 *
 * <p>The {@link RestClient} annotation names this client {@code userService}, which matches the
 * configuration key {@code restClient.userService.baseUrl} in {@code config.json}. The default
 * URL {@code http://localhost:8081} is used when no configuration override is present.
 *
 * <p>A circuit breaker with 5 max failures and a 5-second timeout is applied at the
 * interface level; all methods share this breaker unless overridden per-method.
 */
@RestClient(name = "userService", value = "http://localhost:8081")
@Path("/api/users")
@Produces("application/json")
@Consumes("application/json")
@CircuitBreaker(maxFailures = 5, timeoutMs = 5000)
public interface UserClient {

    /**
     * Lists all users, optionally paginated.
     *
     * @param page the zero-based page number; {@code 0} for the first page
     * @return a future containing the list of users on the requested page
     */
    @GET
    Future<List<User>> list(@QueryParam("page") int page);

    /**
     * Finds a user by ID. Returns {@link Optional#empty()} when the server responds with 404.
     *
     * @param id the user identifier
     * @return a future containing the user wrapped in {@link Optional}, or empty if not found
     */
    @GET
    @Path("/{id}")
    Future<Optional<User>> findById(@PathParam("id") String id);

    /**
     * Creates a new user. Expects HTTP 201 Created from the server.
     *
     * @param user the user data to create; the {@code id} field should be {@code null}
     * @return a future containing the created user with the server-assigned ID
     */
    @POST
    @ExpectedStatus(201)
    Future<User> create(User user);

    /**
     * Deletes a user by ID. Expects HTTP 204 No Content from the server.
     *
     * @param id the user identifier to delete
     * @return a future that completes when the user has been deleted
     */
    @DELETE
    @Path("/{id}")
    @ExpectedStatus(204)
    Future<Void> delete(@PathParam("id") String id);
}
