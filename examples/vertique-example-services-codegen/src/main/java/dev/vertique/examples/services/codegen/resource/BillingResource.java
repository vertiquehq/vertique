// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.resource;

import dev.vertique.examples.services.codegen.service.BillingService;
import dev.vertique.examples.services.codegen.service.ChargeRequest;
import dev.vertique.examples.services.codegen.service.Receipt;
import io.swagger.v3.oas.annotations.Operation;
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
import jakarta.ws.rs.core.MediaType;

/**
 * REST resource exposing billing operations via the event bus service client.
 *
 * <p>Delegates all operations to the injected {@link BillingService} proxy which
 * dispatches calls over the Vert.x event bus to the {@code BillingServiceImpl} running
 * in its own service verticle.
 */
@Path("/billing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Billing", description = "Billing charge endpoints")
public class BillingResource {

    private final BillingService billingService;

    /**
     * Creates a new billing resource.
     *
     * @param billingService the event bus service client for billing operations
     */
    @Inject
    public BillingResource(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * Charges a customer for the given order.
     *
     * @param req the charge request payload
     * @return a future containing the receipt for the completed charge
     */
    @POST
    @Path("/charge")
    @Operation(
            operationId = "charge",
            summary = "Charge for an order",
            description = "Processes a charge and returns a receipt")
    @ApiResponse(
            responseCode = "200",
            description = "Charge processed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Receipt.class)))
    public Future<Receipt> charge(ChargeRequest req) {
        return billingService.charge(req);
    }
}
