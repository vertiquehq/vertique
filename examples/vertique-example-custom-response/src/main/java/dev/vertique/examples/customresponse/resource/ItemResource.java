// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse.resource;

import dev.vertique.examples.customresponse.Item;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory item resource demonstrating JAX-RS CRUD endpoints.
 *
 * <p>Showcases:
 * <ul>
 *   <li>SHA-256 {@code Digest} header validation and emission via {@link dev.vertique.examples.customresponse.DigestFilter}</li>
 *   <li>Categorized error format via {@link dev.vertique.examples.customresponse.CategorizedExceptionMapper}</li>
 *   <li>JWT + RBAC authorization ({@code @RolesAllowed("admin")}) on delete</li>
 * </ul>
 *
 * <p>Class-level {@code @PermitAll} makes all endpoints public by default. The delete
 * endpoint overrides this with {@code @RolesAllowed("admin")}.
 */
@Singleton
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class ItemResource {

    private final Map<String, Item> items = new ConcurrentHashMap<>();

    /**
     * Constructs a new {@code ItemResource} with an empty in-memory store.
     */
    @Inject
    public ItemResource() {}

    /**
     * Returns all items in the store.
     *
     * @return a future containing the list of all items
     */
    @GET
    @Operation(operationId = "listItems", summary = "List all items")
    public Future<List<Item>> listItems() {
        return Future.succeededFuture(List.copyOf(items.values()));
    }

    /**
     * Returns the item with the given ID, or 404 if not found.
     *
     * @param id the item ID
     * @return a future containing the item, or a failed future with {@link NotFoundException}
     */
    @GET
    @Path("/{id}")
    @Operation(operationId = "getItem", summary = "Get an item by ID")
    public Future<Item> getItem(@PathParam("id") String id) {
        Item item = items.get(id);
        if (item == null) {
            return Future.failedFuture(new NotFoundException("Item not found: " + id));
        }
        return Future.succeededFuture(item);
    }

    /**
     * Creates a new item with a generated ID.
     *
     * @param input the item data; {@code name} must not be blank
     * @return a future containing a 201 Created response with the new item
     * @throws IllegalArgumentException if the item name is blank
     */
    @POST
    @Operation(operationId = "createItem", summary = "Create a new item")
    public Future<Response> createItem(Item input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        String id = UUID.randomUUID().toString();
        Item item = new Item(id, input.name());
        items.put(id, item);
        return Future.succeededFuture(Response.status(201)
                .entity(item)
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /**
     * Deletes the item with the given ID. Requires the {@code admin} role.
     *
     * @param id the item ID to delete
     * @return a future that completes with no content on success
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed("admin")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "deleteItem", summary = "Delete an item by ID (admin only)")
    public Future<Void> deleteItem(@PathParam("id") String id) {
        items.remove(id);
        return Future.succeededFuture();
    }
}
