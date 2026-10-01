// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.ReceiptZx;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The resource {@value #ROUTE} of {@link ReceiptsExplicitApi}: {@code GET /receipts} (operation
 * {@value #OPERATION_ID}, the method name). It returns {@code Response} and declares {@code 200}
 * with {@code ReceiptZx} content.
 */
@Path(ReceiptExplicitResource.ROUTE)
public class ReceiptExplicitResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/receipts";

    /** The operation id of {@link #readReceiptExplicitZx}. */
    public static final String OPERATION_ID = "readReceiptExplicitZx";

    /** Creates the resource. */
    public ReceiptExplicitResource() {}

    /**
     * Handles {@code GET /receipts}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReceiptZx.class)))
    public Response readReceiptExplicitZx() {
        return Response.ok(new ReceiptZx(42, "secret")).build();
    }
}
