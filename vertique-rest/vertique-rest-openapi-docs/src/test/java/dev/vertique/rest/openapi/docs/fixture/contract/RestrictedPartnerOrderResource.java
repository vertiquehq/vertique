// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of the restricted {@code partner} declarations: the three operations of {@link
 * PartnerOrderResource}, declared the same way, plus {@code DELETE /orders/{id}} ({@value
 * #CANCEL_ORDER}), which restricts its callers with {@code @SecurityRequirement(name =
 * "bearerAuth")} and {@code @RolesAllowed("admin")}. The other three restrict nobody.
 */
@Path(PartnerOrderResource.ROUTE)
public class RestrictedPartnerOrderResource {

    /** The operation id of {@link #cancelOrder}, the only operation that restricts its callers. */
    public static final String CANCEL_ORDER = "cancelOrder";

    /** Creates the resource. */
    public RestrictedPartnerOrderResource() {}

    /**
     * Handles {@code GET /orders}.
     *
     * @param page  the page number, or {@code null} when absent
     * @param debug the hidden debug switch, or {@code null} when absent
     * @return the fixed body {@value PartnerOrderResource#LIST_BODY}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = PartnerOrderResource.LIST_ORDERS_SUMMARY)
    public String listOrders(
            @QueryParam(PartnerOrderResource.PAGE) Integer page,
            @QueryParam(PartnerOrderResource.DEBUG) @Parameter(hidden = true) Boolean debug) {
        return PartnerOrderResource.LIST_BODY;
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
     * @return the fixed body {@value PartnerOrderResource#ORDER_BODY}
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = PartnerOrderResource.GET_ORDER_INTERNAL, hidden = true)
    public String getOrderInternal(@PathParam("id") String id) {
        return PartnerOrderResource.ORDER_BODY;
    }

    /**
     * Handles {@code DELETE /orders/{id}}; answers with an empty response.
     *
     * @param id the order id
     */
    @DELETE
    @Path("/{id}")
    @Operation(operationId = CANCEL_ORDER)
    @SecurityRequirement(name = GuardedManagementApi.SECURITY_SCHEME)
    @RolesAllowed(GuardedManagementApi.ROLE)
    public void cancelOrder(@PathParam("id") String id) {
        // Nothing is stored: the fixture answers with an empty response.
    }
}
