package ${package}.resource;

import ${package}.model.CreateItemRequest;
import ${package}.model.Item;
import ${package}.model.UpdateItemRequest;
import ${package}.repository.ItemRepository;
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
import java.util.UUID;

/**
 * REST resource exposing item CRUD under {@code /items}.
 *
 * <p>Request validation happens in-resource before delegating to {@link ItemRepository}; a missing
 * item is reported as {@code 404} and a malformed identifier as {@code 400}.
 */
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Singleton
public final class ItemResource {

    private final ItemRepository repository;

    /**
     * Creates a new {@code ItemResource}.
     *
     * @param repository the item repository backing this resource
     */
    @Inject
    public ItemResource(ItemRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a new item.
     *
     * @param request the create request body
     * @return a future completing with {@code 201 Created}, a {@code Location} header, and the item
     * @throws BadRequestException when the name is missing or blank
     */
    @POST
    public Future<Response> create(CreateItemRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        return repository
                .create(request.name(), request.description())
                .map(item -> Response.created(URI.create("/items/" + item.id()))
                        .entity(item)
                        .build());
    }

    /**
     * Returns a single item.
     *
     * @param id the item identifier
     * @return a future completing with {@code 200 OK} and the item
     * @throws BadRequestException when the identifier is not a valid UUID
     * @throws NotFoundException when no such item exists
     */
    @GET
    @Path("/{id}")
    public Future<Response> getById(@PathParam("id") String id) {
        UUID itemId = parseId(id);
        return repository.findById(itemId).map(item -> {
            if (item == null) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Updates an existing item.
     *
     * @param id      the item identifier
     * @param request the update request body
     * @return a future completing with {@code 200 OK} and the updated item
     * @throws BadRequestException when the identifier is not a valid UUID, or the name is missing
     * @throws NotFoundException when no such item exists
     */
    @PUT
    @Path("/{id}")
    public Future<Response> update(@PathParam("id") String id, UpdateItemRequest request) {
        UUID itemId = parseId(id);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        return repository.update(itemId, request.name(), request.description()).map(item -> {
            if (item == null) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Deletes an item.
     *
     * @param id the item identifier
     * @return a future completing with {@code 204 No Content}
     * @throws BadRequestException when the identifier is not a valid UUID
     * @throws NotFoundException when no such item exists
     */
    @DELETE
    @Path("/{id}")
    public Future<Response> delete(@PathParam("id") String id) {
        UUID itemId = parseId(id);
        return repository.delete(itemId).map(deleted -> {
            if (!deleted) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.noContent().build();
        });
    }

    /**
     * Parses a path identifier into a {@link UUID}.
     *
     * @param id the raw path value
     * @return the parsed identifier
     * @throws BadRequestException when the value is not a valid UUID
     */
    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid item id: " + id);
        }
    }
}
