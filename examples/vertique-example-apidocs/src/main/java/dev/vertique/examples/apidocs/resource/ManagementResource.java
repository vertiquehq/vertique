// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Management operations, open only to an authenticated {@code admin}.
 *
 * <p>Every operation requires a bearer token of the {@code bearerAuth} scheme, which the published
 * management document states, and the {@code admin} role, which the runtime enforces but no OpenAPI
 * document can express.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
@RolesAllowed("admin")
public class ManagementResource {

    /** Creates the resource. */
    @Inject
    public ManagementResource() {}

    /**
     * Reports whether the service is up.
     *
     * @return the service status
     */
    @GET
    @Path("/status")
    @Operation(summary = "Report the service status")
    public ServiceStatus getStatus() {
        return new ServiceStatus("UP");
    }

    /**
     * Renames a catalog item.
     *
     * <p>The example keeps no state: it validates the update and returns the item as it would read
     * after the change.
     *
     * @param id     the item identifier
     * @param update the new name of the item
     * @return the updated item
     */
    @PUT
    @Path("/items/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Rename a catalog item")
    public CatalogItem updateItem(
            @PathParam("id") @Parameter(description = "Item identifier") String id, ItemUpdate update) {
        return new CatalogItem(id, update.name());
    }
}
