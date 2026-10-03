// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.ReceiptZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link ReceiptsApi}: {@code GET /receipts} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response.
 */
@Path(ReceiptResource.ROUTE)
public class ReceiptResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/receipts";

    /** The operation id of {@link #readReceiptZx}. */
    public static final String OPERATION_ID = "readReceiptZx";

    /** Creates the resource. */
    public ReceiptResource() {}

    /**
     * Handles {@code GET /receipts}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<ReceiptZx> readReceiptZx() {
        return Future.succeededFuture(new ReceiptZx(42, "secret"));
    }
}
