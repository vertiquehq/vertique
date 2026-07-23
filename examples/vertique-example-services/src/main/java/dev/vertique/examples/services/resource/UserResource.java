// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.resource;

import dev.vertique.examples.services.service.UserResponse;
import dev.vertique.examples.services.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REST resource exposing user operations via the event bus service client.
 *
 * <p>All methods delegate to the injected {@link UserService} proxy which dispatches
 * calls over the Vert.x event bus to the service implementation running in its own verticle.
 */
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Users", description = "User management endpoints")
public class UserResource {

    private final UserService userService;

    /**
     * Creates a new user resource.
     *
     * @param userService the event bus service client for user operations
     */
    @Inject
    UserResource(UserService userService) {
        this.userService = userService;
    }

    /**
     * Retrieves a single user by id.
     *
     * @param id the user identifier
     * @return a future containing the user data
     */
    @GET
    @Path("/{id}")
    @Operation(
            operationId = "getUser",
            summary = "Get a user by id",
            description = "Returns the user with the given identifier, or 404 if not found")
    @ApiResponse(
            responseCode = "200",
            description = "User found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class)))
    @ApiResponse(responseCode = "404", description = "User not found")
    public Future<UserResponse> getUser(
            @Parameter(description = "The user identifier", required = true) @PathParam("id") String id) {
        return userService.getUser(id);
    }

    /**
     * Lists all users.
     *
     * @return a future containing the list of all users
     */
    @GET
    @Operation(operationId = "listUsers", summary = "List all users", description = "Returns all users in the system")
    @ApiResponse(
            responseCode = "200",
            description = "User list returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserResponse.class))))
    public Future<List<UserResponse>> listUsers() {
        return userService.listUsers();
    }

    /**
     * Deletes a user by id.
     *
     * @param id the user identifier
     * @return a future that completes with 204 No Content on success
     */
    @DELETE
    @Path("/{id}")
    @Operation(
            operationId = "deleteUser",
            summary = "Delete a user",
            description = "Removes the user with the given identifier")
    @ApiResponse(responseCode = "204", description = "User deleted")
    public Future<Void> deleteUser(
            @Parameter(description = "The user identifier", required = true) @PathParam("id") String id) {
        return userService.deleteUser(id);
    }
}
