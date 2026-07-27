package ${package}.resource;

import ${package}.model.CreateItemRequest;
import ${package}.model.Item;
import ${package}.model.UpdateItemRequest;
import ${package}.repository.ItemRepository;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
 * <p>Request validation is the framework's, not the resource's: the request-validation gate rejects
 * a body that violates the constraints on {@link CreateItemRequest} and
 * {@link UpdateItemRequest} with {@code 400}, and the built-in {@link UUID} parameter converter
 * rejects a malformed {@code {id}} with {@code 400}, naming the parameter and its target type
 * instead of echoing the raw value. Only the application-specific outcome is left to this class: a
 * missing item is reported as {@code 404}.
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
     * @param request the create request body, already validated against {@link CreateItemRequest}
     * @return a future completing with {@code 201 Created}, a {@code Location} header, and the item
     */
    @POST
    public Future<Response> create(CreateItemRequest request) {
        return repository
                .create(request.name(), request.description())
                .map(item -> Response.created(URI.create("/items/" + item.id()))
                        .entity(item)
                        .build());
    }

    /**
     * Returns a single item.
     *
     * @param id the item identifier, already converted from the path value
     * @return a future completing with {@code 200 OK} and the item
     * @throws NotFoundException when no such item exists
     */
    @GET
    @Path("/{id}")
    public Future<Response> getById(@PathParam("id") UUID id) {
        return repository.findById(id).map(item -> {
            if (item == null) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Updates an existing item.
     *
     * @param id      the item identifier, already converted from the path value
     * @param request the update request body, already validated against {@link UpdateItemRequest}
     * @return a future completing with {@code 200 OK} and the updated item
     * @throws NotFoundException when no such item exists
     */
    @PUT
    @Path("/{id}")
    public Future<Response> update(@PathParam("id") UUID id, UpdateItemRequest request) {
        return repository.update(id, request.name(), request.description()).map(item -> {
            if (item == null) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.ok(item).build();
        });
    }

    /**
     * Deletes an item.
     *
     * @param id the item identifier, already converted from the path value
     * @return a future completing with {@code 204 No Content}
     * @throws NotFoundException when no such item exists
     */
    @DELETE
    @Path("/{id}")
    public Future<Response> delete(@PathParam("id") UUID id) {
        return repository.delete(id).map(deleted -> {
            if (!deleted) {
                throw new NotFoundException("item not found: " + id);
            }
            return Response.noContent().build();
        });
    }
}
