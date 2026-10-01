// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.TierReceiptZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.TierZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link TiersApi}: {@code GET /tiers} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response.
 */
@Path(TierResource.ROUTE)
public class TierResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/tiers";

    /** The operation id of {@link #readTierZx}. */
    public static final String OPERATION_ID = "readTierZx";

    /** Creates the resource. */
    public TierResource() {}

    /**
     * Handles {@code GET /tiers}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<TierReceiptZx> readTierZx() {
        return Future.succeededFuture(new TierReceiptZx(TierZx.PUBLIC_ZX));
    }
}
