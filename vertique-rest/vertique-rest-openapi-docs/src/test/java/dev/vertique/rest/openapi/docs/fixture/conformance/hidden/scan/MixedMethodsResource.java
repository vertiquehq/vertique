// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden.scan;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE}: a method hidden by {@code @Operation(hidden = true)}, two methods
 * hidden by {@code @Hidden}, and one visible method. Every method is {@code void} and takes nothing,
 * so a completed request answers with no content.
 */
@Path(MixedMethodsResource.ROUTE)
public class MixedMethodsResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/a";

    /** Creates the resource. */
    public MixedMethodsResource() {}

    /** Handles {@code GET /a/one}, hidden by its {@code @Operation}. */
    @GET
    @Path("/one")
    @Operation(operationId = "scanReadOne", hidden = true)
    public void readOne() {
        // Nothing to do: a request only proves the route still answers.
    }

    /** Handles {@code POST /a/two}, hidden by {@code @Hidden}. */
    @POST
    @Path("/two")
    @Hidden
    @Operation(operationId = "scanWriteTwo")
    public void writeTwo() {
        // Nothing to do: a request only proves the route still answers.
    }

    /** Handles {@code GET /a/three}, visible. */
    @GET
    @Path("/three")
    @Operation(operationId = "scanReadThree")
    public void readThree() {
        // Nothing to do: the operation only has to be published.
    }

    /** Handles {@code GET /a/four}, hidden by {@code @Hidden}. */
    @GET
    @Path("/four")
    @Hidden
    @Operation(operationId = "scanReadFour")
    public void readFour() {
        // Nothing to do: a request only proves the route still answers.
    }
}
