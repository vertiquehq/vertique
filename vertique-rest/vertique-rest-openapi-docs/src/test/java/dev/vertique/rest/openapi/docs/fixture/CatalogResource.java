// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link PublicApi}: three operations under {@code /items}. Every method returns
 * {@code void} or {@code String}; no member carries a security requirement, a schema rename, or a
 * hiding marker, and the {@code @Operation} annotations set only a summary.
 */
@Tag(name = CatalogResource.TAG)
@Path("/items")
public class CatalogResource {

    /** The tag the resource carries. */
    public static final String TAG = "catalog";

    /** The operation id of {@link #listItems}. */
    public static final String LIST_ITEMS = "listItems";

    /** The operation id of {@link #getItem}. */
    public static final String GET_ITEM = "getItem";

    /** The operation id of {@link #createItem}. */
    public static final String CREATE_ITEM = "createItem";

    /** The number of operations the resource declares. */
    public static final int OPERATION_COUNT = 3;

    /** The fixed body {@link #listItems} and {@link #getItem} return. */
    public static final String BODY = "items";

    /** Public {@code @Inject} constructor. */
    @Inject
    public CatalogResource() {}

    /**
     * Handles {@code GET /items}.
     *
     * @param limit the maximum number of items, or {@code null} when absent
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "List items")
    public String listItems(@QueryParam("limit") Integer limit) {
        return BODY;
    }

    /**
     * Handles {@code GET /items/{id}}.
     *
     * @param id the item id
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "Get an item")
    public String getItem(@PathParam("id") String id) {
        return BODY;
    }

    /**
     * Handles {@code POST /items}.
     *
     * @param dryRun  whether the item is only validated, not created
     * @param request the item to create
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create an item")
    public void createItem(@QueryParam("dryRun") boolean dryRun, CreateItemRequest request) {
        // Nothing is stored: the fixture answers with an empty response.
    }
}
