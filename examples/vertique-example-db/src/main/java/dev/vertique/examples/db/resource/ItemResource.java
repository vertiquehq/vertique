// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.examples.db.model.CreateItemRequest;
import dev.vertique.examples.db.model.Item;
import dev.vertique.examples.db.model.UpdateItemRequest;
import dev.vertique.examples.db.repository.ItemRepository;
import dev.vertique.rest.core.pagination.CursorPageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST resource for item CRUD operations.
 *
 * <p>Exposes endpoints under {@code /items} for creating, listing, retrieving, updating, and
 * deleting items. All responses use JSON. Request validation is performed in-resource before
 * delegating to {@link ItemRepository}.
 */
@Tag(name = "Items")
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Singleton
public class ItemResource {

    private final ItemRepository repository;

    /**
     * Creates a new {@code ItemResource}.
     *
     * @param repository the item repository for database operations
     */
    @Inject
    public ItemResource(ItemRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a new item.
     *
     * @param request the create item request body
     * @return a future containing the 201 Created response with the created item
     * @throws BadRequestException if the name is missing or blank
     */
    @POST
    @Operation(operationId = "createItem", summary = "Create a new item")
    @ApiResponse(responseCode = "201", description = "Item created")
    public Future<Response> create(CreateItemRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        return repository.create(request.name(), request.description()).map(item -> Response.created(
                        URI.create("/items/" + item.id()))
                .entity(item)
                .build());
    }

    /**
     * Lists all items ordered by name.
     *
     * @return a future containing the list of all items
     */
    @GET
    @Operation(operationId = "listItems", summary = "List all items")
    public Future<List<Item>> list() {
        return repository.findAll();
    }

    /**
     * Returns a paginated list of items using keyset pagination. Items are sorted by name
     * (ascending) with ID as a tiebreaker.
     *
     * <p>On the first page, omit {@code cursor} and optionally supply {@code pageSize} (default 20,
     * range 1–100). On subsequent pages, pass the {@code nextCursorToken} or
     * {@code previousCursorToken} from the previous result. A {@code pageSize} query parameter on a
     * cursor-based request overrides the embedded page size.
     *
     * @param pageRequest composite request object carrying the {@code cursor} and {@code pageSize}
     *                    query parameters; populated automatically by the framework
     * @return a future containing the paged result with items and navigation cursors
     * @throws BadRequestException if the cursor token is malformed or the page size is out of range
     */
    @GET
    @Path("/page")
    @Operation(operationId = "listItemsPaged", summary = "List items with keyset pagination")
    public Future<PagedResult<Item>> listPaged(CursorPageRequest pageRequest) {
        String cursor = pageRequest.cursor();
        Integer pageSizeParam = pageRequest.pageSize();

        PageCursor pageCursor;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                pageCursor = PageCursor.fromToken(cursor);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid cursor: " + e.getMessage());
            }
        } else {
            pageCursor = PageCursor.first(20);
        }

        // Apply page-size override or validate cursor-embedded size
        if (pageSizeParam != null) {
            pageCursor = pageCursor.withPageSize(pageSizeParam, 1, 100);
        } else {
            pageCursor = pageCursor.validatePageSize(1, 100);
        }

        return repository.findItems(pageCursor);
    }

    /**
     * Retrieves a single item by ID.
     *
     * @param id the item UUID as a string
     * @return a future containing the 200 OK response with the item
     * @throws BadRequestException if the ID is not a valid UUID
     * @throws NotFoundException   if no item with the given ID exists
     */
    @GET
    @Path("/{id}")
    @Operation(operationId = "getItem", summary = "Get an item by ID")
    @ApiResponse(responseCode = "200", description = "Item found")
    @ApiResponse(responseCode = "404", description = "Item not found")
    public Future<Response> getById(@Parameter(description = "Item ID") @PathParam("id") String id) {
        UUID uuid = parseUuid(id);
        return repository.findById(uuid).map(item -> {
            if (item == null) {
                throw new NotFoundException("Item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Updates an existing item.
     *
     * @param id      the item UUID as a string
     * @param request the update item request body
     * @return a future containing the 200 OK response with the updated item
     * @throws BadRequestException if the ID is not a valid UUID or the name is missing
     * @throws NotFoundException   if no item with the given ID exists
     */
    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateItem", summary = "Update an item")
    @ApiResponse(responseCode = "200", description = "Item updated")
    @ApiResponse(responseCode = "404", description = "Item not found")
    public Future<Response> update(
            @Parameter(description = "Item ID") @PathParam("id") String id, UpdateItemRequest request) {
        UUID uuid = parseUuid(id);
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        return repository.update(uuid, request.name(), request.description()).map(item -> {
            if (item == null) {
                throw new NotFoundException("Item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Deletes an item by ID.
     *
     * @param id the item UUID as a string
     * @return a future containing the 204 No Content response on success
     * @throws BadRequestException if the ID is not a valid UUID
     * @throws NotFoundException   if no item with the given ID exists
     */
    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteItem", summary = "Delete an item")
    @ApiResponse(responseCode = "204", description = "Item deleted")
    @ApiResponse(responseCode = "404", description = "Item not found")
    public Future<Response> delete(@Parameter(description = "Item ID") @PathParam("id") String id) {
        UUID uuid = parseUuid(id);
        return repository.delete(uuid).map(deleted -> {
            if (!deleted) {
                throw new NotFoundException("Item not found: " + id);
            }
            return Response.noContent().build();
        });
    }

    // --- Helpers ---

    /**
     * Parses a UUID from a string, throwing {@link BadRequestException} for invalid values.
     *
     * @param id the string to parse
     * @return the parsed UUID
     * @throws BadRequestException if the string is not a valid UUID
     */
    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid UUID: " + id);
        }
    }
}
