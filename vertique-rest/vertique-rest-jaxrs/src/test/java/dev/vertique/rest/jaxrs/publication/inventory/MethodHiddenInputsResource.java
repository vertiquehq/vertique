// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose operations hide inputs from the method rather than from the input itself: a
 * method-level {@code @Parameter}, a {@code @Parameters} container, and {@code @Operation(parameters
 * = ...)}, each naming an input by name and location. Every operation also binds an input no entry
 * hides, and one operation declares a visible entry that names no input.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/method-hidden")
public class MethodHiddenInputsResource {

    /**
     * {@code GET /method-hidden/header}: a method-level {@code @Parameter} hides the header.
     *
     * @param token header {@code X-Debug-Token}, hidden by the method (index 0)
     * @param page  query {@code page}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/header")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "X-Debug-Token", in = ParameterIn.HEADER, hidden = true)
    public String hideHeaderByMethod(@HeaderParam("X-Debug-Token") String token, @QueryParam("page") String page) {
        return "header";
    }

    /**
     * {@code GET /method-hidden/container}: a {@code @Parameters} container hides {@code a} and
     * declares {@code b} visible.
     *
     * @param a query {@code a}, hidden by the container (index 0)
     * @param b query {@code b}, declared visible by the container (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/container")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameters({
        @Parameter(name = "a", in = ParameterIn.QUERY, hidden = true),
        @Parameter(name = "b", in = ParameterIn.QUERY, hidden = false)
    })
    public String hideByContainer(@QueryParam("a") String a, @QueryParam("b") String b) {
        return "container";
    }

    /**
     * {@code GET /method-hidden/operation}: {@code @Operation(parameters = ...)} hides {@code c}.
     *
     * @param c query {@code c}, hidden by the operation (index 0)
     * @param d query {@code d}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Path("/operation")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(
            operationId = "hideByOperation",
            parameters = @Parameter(name = "c", in = ParameterIn.QUERY, hidden = true))
    public String hideByOperation(@QueryParam("c") String c, @QueryParam("d") String d) {
        return "operation";
    }

    /**
     * {@code GET /method-hidden/composite}: a method-level {@code @Parameter} hides a field the
     * composite binds.
     *
     * @param bean composite binding query {@code internal}, hidden by the method, and query
     *             {@code external}, not hidden (index 0)
     * @return a fixed body
     */
    @GET
    @Path("/composite")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "internal", in = ParameterIn.QUERY, hidden = true)
    public String hideCompositeField(@BeanParam MethodHiddenBean bean) {
        return "composite";
    }

    /**
     * {@code GET /method-hidden/visible}: a visible method-level {@code @Parameter} names no input
     * the method binds.
     *
     * @param w query {@code w}, not hidden (index 0)
     * @return a fixed body
     */
    @GET
    @Path("/visible")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "v", in = ParameterIn.QUERY, hidden = false)
    public String declareVisibleUnbound(@QueryParam("w") String w) {
        return "visible";
    }
}
