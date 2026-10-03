// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE}, hidden by {@code @Hidden} on the class alone; its method carries no
 * hiding marker. The method is {@code void}, so a completed request answers with no content.
 */
@Hidden
@Path(HiddenTypeResource.ROUTE)
public class HiddenTypeResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/b";

    /** Creates the resource. */
    public HiddenTypeResource() {}

    /** Handles {@code GET /b}. */
    @GET
    @Operation(operationId = "scanReadHiddenType")
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
