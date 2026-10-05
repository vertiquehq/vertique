// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.security.authz.Authorized;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

/**
 * The IT's twin resource at {@code /api/mgmt/*}: {@code GET /doc} mirrors {@code
 * apidocs:management:json} ({@code @SecurityRequirement} + {@code @RolesAllowed("admin")}), and
 * {@code GET /auth-only} mirrors {@code apidocs:authenticated:json} ({@code @SecurityRequirement}
 * + {@code @Authorized}). Each method appends {@code "terminal"} to the shared {@link
 * TraceRecorder}, exactly as the synthetic terminal does, so a trace comparison between a document
 * and its twin is meaningful.
 */
@Path("")
public final class TwinResource {

    private final TraceRecorder trace;

    /**
     * Creates the resource.
     *
     * @param trace the shared trace recorder
     */
    public TwinResource(TraceRecorder trace) {
        this.trace = trace;
    }

    /**
     * Mirrors {@code apidocs:management:json}.
     *
     * @param ctx the routing context, used only to append to the trace
     * @return a fixed body
     */
    @GET
    @Path("/doc")
    @SecurityRequirement(name = SyntheticDocsMount.SCHEME)
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "doc")
    public String doc(@Context RoutingContext ctx) {
        trace.record(ctx, "terminal");
        return "doc-body";
    }

    /**
     * Mirrors {@code apidocs:authenticated:json}.
     *
     * @param ctx the routing context, used only to append to the trace
     * @return a fixed body
     */
    @GET
    @Path("/auth-only")
    @SecurityRequirement(name = SyntheticDocsMount.SCHEME)
    @Authorized
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "authOnly")
    public String authOnly(@Context RoutingContext ctx) {
        trace.record(ctx, "terminal");
        return "auth-only-body";
    }
}
