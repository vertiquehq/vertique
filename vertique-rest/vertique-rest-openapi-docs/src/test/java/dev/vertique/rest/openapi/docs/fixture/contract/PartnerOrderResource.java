// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of the application {@code partner}: three operations under {@value #ROUTE}.
 *
 * <ul>
 *   <li>{@code GET /orders} ({@value #LIST_ORDERS}): query {@value #PAGE}, and query {@value #DEBUG}
 *       hidden by {@code @Parameter(hidden = true)}; its {@code @Operation} sets only a summary,
 *       which no partner contract states;
 *   <li>{@code POST /orders} ({@value #CREATE_ORDER}): a JSON {@link PartnerOrderRequest} body whose
 *       {@code sku} is required; answers with an empty response;
 *   <li>{@code GET /orders/{id}} ({@value #GET_ORDER_INTERNAL}): hidden by {@code @Operation(hidden =
 *       true)}.
 * </ul>
 */
@Path(PartnerOrderResource.ROUTE)
public class PartnerOrderResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/orders";

    /** The operation id of {@link #listOrders}. */
    public static final String LIST_ORDERS = "listOrders";

    /** The operation id of {@link #createOrder}. */
    public static final String CREATE_ORDER = "createOrder";

    /** The operation id of {@link #getOrderInternal}, a hidden operation. */
    public static final String GET_ORDER_INTERNAL = "getOrderInternal";

    /** The summary {@link #listOrders} declares and no partner contract states. */
    public static final String LIST_ORDERS_SUMMARY = "List partner orders";

    /** The visible query parameter of {@link #listOrders}. */
    public static final String PAGE = "page";

    /** The hidden query parameter of {@link #listOrders}. */
    public static final String DEBUG = "debug";

    /** The fixed JSON body {@link #listOrders} returns. */
    public static final String LIST_BODY = "[]";

    /** The fixed JSON body {@link #getOrderInternal} returns. */
    public static final String ORDER_BODY = "{}";

    /** Creates the resource. */
    public PartnerOrderResource() {}

    /**
     * Handles {@code GET /orders}.
     *
     * @param page  the page number, or {@code null} when absent
     * @param debug the hidden debug switch, or {@code null} when absent
     * @return the fixed body {@value #LIST_BODY}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = LIST_ORDERS_SUMMARY)
    public String listOrders(
            @QueryParam(PAGE) Integer page, @QueryParam(DEBUG) @Parameter(hidden = true) Boolean debug) {
        return LIST_BODY;
    }

    /**
     * Handles {@code POST /orders}; answers with an empty response.
     *
     * @param request the order to create
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createOrder(PartnerOrderRequest request) {
        // Nothing is stored: the fixture answers with an empty response.
    }

    /**
     * Handles {@code GET /orders/{id}}, a hidden operation.
     *
     * @param id the order id
     * @return the fixed body {@value #ORDER_BODY}
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = GET_ORDER_INTERNAL, hidden = true)
    public String getOrderInternal(@PathParam("id") String id) {
        return ORDER_BODY;
    }
}
