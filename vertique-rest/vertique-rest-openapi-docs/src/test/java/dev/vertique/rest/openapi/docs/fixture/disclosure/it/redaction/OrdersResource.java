// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/** The resource of {@link OrdersApi}: one operation taking an {@link OrderZx} body. */
@Path("/orders")
public class OrdersResource {

    /** The operation id of {@link #createOrder}. */
    public static final String CREATE_ORDER = "createOrderZx";

    /** The operation's path relative to the application's path. */
    public static final String ROUTE = "/orders";

    /** Creates the resource. */
    public OrdersResource() {}

    /**
     * Handles {@code POST /orders}; answers with an empty response.
     *
     * @param order the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = CREATE_ORDER)
    public void createOrder(OrderZx order) {
        // Nothing to do: the gate decides every answer the tests observe.
    }
}
