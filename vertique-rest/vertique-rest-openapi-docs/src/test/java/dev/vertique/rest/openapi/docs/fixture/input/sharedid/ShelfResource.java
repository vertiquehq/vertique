// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.sharedid;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/**
 * The resource of {@link StoreApi}: its one operation has the id {@link #LIST}, which a resource of
 * another application shares.
 */
@Path(ShelfResource.ROUTE)
public class ShelfResource {

    /** The resource's route, relative to the application's mount. */
    public static final String ROUTE = "/shelf";

    /** The operation id of {@link #list}, shared with the other application's resource. */
    public static final String LIST = "list";

    /** The name of the query parameter of {@link #list}, distinct from the other application's. */
    public static final String PARAMETER = "shelfPage";

    /** Creates the resource. */
    public ShelfResource() {}

    /**
     * Handles {@code GET /shelf}; answers with an empty response.
     *
     * @param page query {@code shelfPage}
     */
    @GET
    @Operation(operationId = LIST)
    public void list(@QueryParam(PARAMETER) Integer page) {
        // Nothing to do: no test sends a request to this operation.
    }
}
