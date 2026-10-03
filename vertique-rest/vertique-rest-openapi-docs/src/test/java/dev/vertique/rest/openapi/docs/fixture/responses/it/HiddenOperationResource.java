// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.ReceiptZx;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link HiddenOpApi}: a hidden operation whose output type would
 * be refused were it published, beside a visible operation.
 *
 * <p>{@code GET /hiddenop/receipt} (operation {@value #HIDDEN_OPERATION_ID}) carries
 * {@code @Operation(hidden = true)}, with no operation id of its own, and returns {@code
 * Future<ReceiptZx>};
 * {@code GET /hiddenop/visible} (operation {@value #VISIBLE_OPERATION_ID}) returns {@code void}, so
 * a request answers {@code 204}. Operation ids are the method names.
 */
@Path(HiddenOperationResource.ROUTE)
public class HiddenOperationResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/hiddenop";

    /** The path of {@link #readHiddenZx}, relative to the resource. */
    public static final String HIDDEN_PATH = "/receipt";

    /** The path of {@link #readVisible}, relative to the resource. */
    public static final String VISIBLE_PATH = "/visible";

    /** The operation id of {@link #readHiddenZx}. */
    public static final String HIDDEN_OPERATION_ID = "readHiddenZx";

    /** The operation id of {@link #readVisible}. */
    public static final String VISIBLE_OPERATION_ID = "readVisible";

    /** Creates the resource. */
    public HiddenOperationResource() {}

    /**
     * Handles {@code GET /hiddenop/receipt}, hidden from the document.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    @Path(HIDDEN_PATH)
    @Operation(hidden = true)
    public Future<ReceiptZx> readHiddenZx() {
        return Future.succeededFuture(new ReceiptZx(42, "secret"));
    }

    /** Handles {@code GET /hiddenop/visible}; answers with an empty response. */
    @GET
    @Path(VISIBLE_PATH)
    public void readVisible() {
        // Nothing to do: a request only proves the route answers.
    }
}
