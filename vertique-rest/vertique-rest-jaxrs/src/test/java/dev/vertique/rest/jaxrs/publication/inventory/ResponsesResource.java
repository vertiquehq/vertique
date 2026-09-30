// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.core.json.JsonProfile;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Resource with one operation per response shape, under a class-level {@link JsonProfile}; one
 * operation overrides it with a method-level {@link JsonProfile}.
 */
@Path("/responses")
@JsonProfile(ResponsesResource.CLASS_PROFILE_ID)
public class ResponsesResource {

    /** The class-level profile id. */
    public static final String CLASS_PROFILE_ID = "inventory-class-profile";

    /** The method-level profile id, declared on {@link #item()} only. */
    public static final String METHOD_PROFILE_ID = "inventory-method-profile";

    /**
     * Returns a plain DTO, under the method-level profile.
     *
     * @return an item
     */
    @GET
    @Path("/item")
    @JsonProfile(METHOD_PROFILE_ID)
    public Item item() {
        return new Item("1", "one");
    }

    /**
     * Returns a future DTO.
     *
     * @return a future item
     */
    @GET
    @Path("/future-item")
    public Future<Item> futureItem() {
        return Future.succeededFuture(new Item("1", "one"));
    }

    /**
     * Returns a future list of DTOs.
     *
     * @return a future item list
     */
    @GET
    @Path("/future-items")
    public Future<List<Item>> futureItems() {
        return Future.succeededFuture(List.of(new Item("1", "one")));
    }

    /** Returns nothing. */
    @GET
    @Path("/nothing")
    public void nothing() {
        // no content
    }

    /**
     * Returns a future that completes with no value.
     *
     * @return a completed future
     */
    @GET
    @Path("/future-void")
    public Future<Void> futureVoid() {
        return Future.succeededFuture();
    }

    /**
     * Returns a JAX-RS response.
     *
     * @return an empty OK response
     */
    @GET
    @Path("/response")
    public Response response() {
        return Response.ok().build();
    }

    /**
     * Returns plain text with a declared produces type.
     *
     * @return a fixed text
     */
    @GET
    @Path("/text")
    @Produces(MediaType.TEXT_PLAIN)
    public String text() {
        return "text";
    }
}
