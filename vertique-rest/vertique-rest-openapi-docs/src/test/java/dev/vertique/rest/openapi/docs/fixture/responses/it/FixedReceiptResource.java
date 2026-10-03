// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.FixedReceiptZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link FixedApi}: {@code GET /fixed} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response; the response body still carries the
 * member the document leaves out.
 */
@Path(FixedReceiptResource.ROUTE)
public class FixedReceiptResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/fixed";

    /** The operation id of {@link #readFixedZx}. */
    public static final String OPERATION_ID = "readFixedZx";

    /** Creates the resource. */
    public FixedReceiptResource() {}

    /**
     * Handles {@code GET /fixed}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<FixedReceiptZx> readFixedZx() {
        return Future.succeededFuture(new FixedReceiptZx(42, "secret"));
    }
}
