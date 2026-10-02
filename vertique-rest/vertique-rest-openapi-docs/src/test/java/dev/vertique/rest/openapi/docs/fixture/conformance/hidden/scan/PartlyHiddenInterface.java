// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS interface whose method {@link #hiddenRead} carries {@code @Hidden} and whose method {@link
 * #visibleRead} carries no hiding marker, implemented by {@link PartlyHiddenInterfaceResource}, which
 * carries no annotation of its own. Both methods are {@code void}, so a completed request answers with
 * no content.
 */
@Path(PartlyHiddenInterface.ROUTE)
public interface PartlyHiddenInterface {

    /** The resource path, relative to the mount. */
    String ROUTE = "/d";

    /** Handles {@code GET /d/x}, hidden on the interface method. */
    @GET
    @Path("/x")
    @Hidden
    @Operation(operationId = "scanReadHiddenMethod")
    void hiddenRead();

    /** Handles {@code GET /d/y}, visible. */
    @GET
    @Path("/y")
    @Operation(operationId = "scanReadVisibleMethod")
    void visibleRead();
}
