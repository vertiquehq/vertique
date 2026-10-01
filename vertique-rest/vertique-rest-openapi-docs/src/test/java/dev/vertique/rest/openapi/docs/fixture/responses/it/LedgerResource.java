// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.AuditZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.LedgerZx;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link LedgerApi}: {@code GET /ledger} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response.
 */
@Path(LedgerResource.ROUTE)
public class LedgerResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/ledger";

    /** The operation id of {@link #readLedgerZx}. */
    public static final String OPERATION_ID = "readLedgerZx";

    /** Creates the resource. */
    public LedgerResource() {}

    /**
     * Handles {@code GET /ledger}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<LedgerZx> readLedgerZx() {
        return Future.succeededFuture(new LedgerZx(new AuditZx("auditor")));
    }
}
