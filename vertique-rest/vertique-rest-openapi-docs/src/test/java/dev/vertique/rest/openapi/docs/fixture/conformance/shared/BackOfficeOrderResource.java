// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link BackOfficeApi}: two operations under {@code /orders}, each guarded by
 * {@code @SecurityRequirement(name = "bearerAuth")} and {@code @RolesAllowed("admin")}, with a JSON
 * request body, a path parameter, and inferred JSON responses.
 */
@Tag(name = BackOfficeOrderResource.TAG)
@Path("/orders")
public class BackOfficeOrderResource {

    /** The tag the resource carries. */
    public static final String TAG = "orders";

    /** The operation id of {@link #createOrder}. */
    public static final String CREATE_ORDER = "createOrder";

    /** The operation id of {@link #readOrder}. */
    public static final String READ_ORDER = "readOrder";

    /** The id every created order receives. */
    public static final String CREATED_ID = "order-1";

    /** Creates the resource. */
    public BackOfficeOrderResource() {}

    /**
     * Handles {@code POST /orders}.
     *
     * @param order the order to create
     * @return the receipt of the created order
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = CREATE_ORDER, summary = "Create an order")
    @SecurityRequirement(name = BackOfficeApi.SECURITY_SCHEME)
    @RolesAllowed(BackOfficeApi.ROLE)
    public Future<OrderReceipt> createOrder(OrderV1 order) {
        return Future.succeededFuture(new OrderReceipt(CREATED_ID, "created"));
    }

    /**
     * Handles {@code GET /orders/{id}}.
     *
     * @param id the order id
     * @return the order's receipt
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = READ_ORDER, summary = "Read an order")
    @SecurityRequirement(name = BackOfficeApi.SECURITY_SCHEME)
    @RolesAllowed(BackOfficeApi.ROLE)
    public Future<OrderReceipt> readOrder(@PathParam("id") String id) {
        return Future.succeededFuture(new OrderReceipt(id, "open"));
    }
}
