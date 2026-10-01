// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.PinReceiptZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link PinsApi}: {@code GET /pins} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response.
 */
@Path(PinResource.ROUTE)
public class PinResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/pins";

    /** The operation id of {@link #readPinZx}. */
    public static final String OPERATION_ID = "readPinZx";

    /** Creates the resource. */
    public PinResource() {}

    /**
     * Handles {@code GET /pins}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<PinReceiptZx> readPinZx() {
        PinReceiptZx receipt = new PinReceiptZx();
        receipt.setPinZx("1234");
        return Future.succeededFuture(receipt);
    }
}
