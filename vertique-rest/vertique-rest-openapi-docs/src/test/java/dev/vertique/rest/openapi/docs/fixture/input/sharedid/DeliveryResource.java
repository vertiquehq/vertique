// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.sharedid;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/**
 * The resource of {@link SupplierApi}: its one operation has the id {@link #LIST}, which a resource of
 * another application shares.
 */
@Path(DeliveryResource.ROUTE)
public class DeliveryResource {

    /** The resource's route, relative to the application's mount. */
    public static final String ROUTE = "/deliveries";

    /** The operation id of {@link #list}, shared with the other application's resource. */
    public static final String LIST = "list";

    /** The name of the query parameter of {@link #list}, distinct from the other application's. */
    public static final String PARAMETER = "deliveryPage";

    /** Creates the resource. */
    public DeliveryResource() {}

    /**
     * Handles {@code GET /deliveries}; answers with an empty response.
     *
     * @param page query {@code deliveryPage}
     */
    @GET
    @Operation(operationId = LIST)
    public void list(@QueryParam(PARAMETER) Integer page) {
        // Nothing to do: no test sends a request to this operation.
    }
}
