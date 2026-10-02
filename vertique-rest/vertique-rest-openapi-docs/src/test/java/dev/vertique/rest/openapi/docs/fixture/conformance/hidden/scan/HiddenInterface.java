// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS interface hidden by {@code @Hidden} on the interface alone, implemented by {@link
 * HiddenInterfaceResource}, which carries no annotation of its own.
 */
@Hidden
@Path(HiddenInterface.ROUTE)
public interface HiddenInterface {

    /** The resource path, relative to the mount. */
    String ROUTE = "/c";

    /** Handles {@code GET /c}; {@code void}, so a completed request answers with no content. */
    @GET
    @Operation(operationId = "scanReadHiddenInterface")
    void read();
}
