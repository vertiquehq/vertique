// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewA;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewB;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The resource {@value #ROUTE} of {@link AccountsApi}: a dynamic response and a response whose
 * declared content differs from the return type.
 *
 * <p>{@code GET /reports/dynamic} (operation {@value #DYNAMIC}) returns a {@code Response} and
 * declares no response metadata. {@code GET /reports/explicit} (operation {@value #EXPLICIT})
 * returns {@code Future<ViewA>} but declares {@code 200} with {@link ViewB} content and {@code 404}
 * without content. Both answer {@code 200} with a {@code ViewA} body. Operation ids are the method
 * names.
 */
@Path(ReportResource.ROUTE)
public class ReportResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/reports";

    /** The path of {@link #dynamic}, relative to the resource. */
    public static final String DYNAMIC_PATH = "/dynamic";

    /** The path of {@link #explicit}, relative to the resource. */
    public static final String EXPLICIT_PATH = "/explicit";

    /** The operation id of {@link #dynamic}. */
    public static final String DYNAMIC = "dynamic";

    /** The operation id of {@link #explicit}. */
    public static final String EXPLICIT = "explicit";

    /** Creates the resource. */
    public ReportResource() {}

    /**
     * Handles {@code GET /reports/dynamic}.
     *
     * @return a {@code 200} response with a {@code ViewA} entity
     */
    @GET
    @Path(DYNAMIC_PATH)
    public Response dynamic() {
        return Response.ok(new ViewA("dynamic", 1)).build();
    }

    /**
     * Handles {@code GET /reports/explicit}.
     *
     * @return a {@code ViewA}, although the declared {@code 200} content is {@code ViewB}
     */
    @GET
    @Path(EXPLICIT_PATH)
    @ApiResponse(
            responseCode = "200",
            description = "The B view",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ViewB.class)))
    @ApiResponse(responseCode = "404", description = "Not found")
    public Future<ViewA> explicit() {
        return Future.succeededFuture(new ViewA("explicit", 2));
    }
}
