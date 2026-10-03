// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.a;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link PublicApi}: lists the catalog and creates an {@link Item}.
 */
@Path(CatalogResource.ROUTE)
public class CatalogResource {

    /** The resource's route, relative to the application's mount. */
    public static final String ROUTE = "/catalog";

    /** The operation id of {@link #list}. */
    public static final String LIST = "listPublic";

    /** The operation id of {@link #create}. */
    public static final String CREATE = "createItem";

    /** The name of the query parameter of {@link #list}. */
    public static final String PAGE = "page";

    /** Creates the resource. */
    public CatalogResource() {}

    /**
     * Handles {@code GET /catalog}; answers with an empty response.
     *
     * @param page query {@code page}
     */
    @GET
    @Operation(operationId = LIST)
    public void list(@QueryParam(PAGE) Integer page) {
        // Nothing to do: no test sends a request to this operation.
    }

    /**
     * Handles {@code POST /catalog}; answers with an empty response.
     *
     * @param item the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = CREATE)
    public void create(Item item) {
        // Nothing to do: no test sends a request to this operation.
    }
}
