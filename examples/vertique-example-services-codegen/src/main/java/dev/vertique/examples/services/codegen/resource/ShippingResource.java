// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.resource;

import dev.vertique.examples.services.codegen.service.ShipmentReceipt;
import dev.vertique.examples.services.codegen.service.ShipmentRequest;
import dev.vertique.examples.services.codegen.service.ShippingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * REST resource exposing shipping operations via the event bus service client.
 *
 * <p>Delegates all operations to the injected {@link ShippingService} proxy which
 * dispatches calls over the Vert.x event bus to the {@code ShippingServiceHandler} running
 * in its own service verticle.
 */
@Path("/shipping")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Shipping", description = "Shipment dispatch endpoints")
public class ShippingResource {

    private final ShippingService shippingService;

    /**
     * Creates a new shipping resource.
     *
     * @param shippingService the event bus service client for shipping operations
     */
    @Inject
    public ShippingResource(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    /**
     * Dispatches a shipment for the given order.
     *
     * @param req the shipment request payload
     * @return a future containing the shipment receipt with tracking information
     */
    @POST
    @Path("/ship")
    @Operation(
            operationId = "ship",
            summary = "Dispatch a shipment",
            description = "Dispatches a shipment for the given order and returns a receipt with tracking info")
    @ApiResponse(
            responseCode = "200",
            description = "Shipment dispatched",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ShipmentReceipt.class)))
    public Future<ShipmentReceipt> ship(ShipmentRequest req) {
        return shippingService.ship(req);
    }

    /**
     * Sends a fire-and-forget dispatch notification for the given order.
     *
     * @param orderId the order identifier to notify about
     * @return a future that completes after the notification is sent
     */
    @POST
    @Path("/notify")
    @Operation(
            operationId = "notifyDispatch",
            summary = "Notify shipment dispatch",
            description = "Sends a one-way dispatch notification; no acknowledgement is returned")
    @ApiResponse(responseCode = "204", description = "Notification sent")
    public Future<Void> notify(
            @Parameter(description = "The order identifier", required = true) @QueryParam("orderId") String orderId) {
        return shippingService.notifyDispatch(orderId);
    }
}
