// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * T006 TP-001's single resource for the hand-built {@code /api/mgmt/*} mount: one plain success
 * operation, one {@code HEAD}-only operation, and one operation that itself returns a {@code 400}
 * (TP-007's fixed request table exercises all three, plus a {@code 405} by sending an unsupported
 * method against {@link #echo()}'s path and a {@code 404} against an undeclared path).
 *
 * <p>Every operation declares its own path segment so {@link #echo()}, {@link #echoAgain()},
 * {@link #head()}, and {@link #bad()} sort into a fixed, reflection-order-independent registration
 * order under the most-specific-path-first rule (T006 E27).
 */
@Path("/echo")
public class MgmtResource {

    /**
     * Plain success operation.
     *
     * @return the fixed body {@code "echo"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String echo() {
        return "echo";
    }

    /**
     * A second plain success operation, at a more specific path than {@link #echo()}.
     *
     * @return the fixed body {@code "echo-again"}
     */
    @GET
    @Path("/again")
    @Produces(MediaType.TEXT_PLAIN)
    public String echoAgain() {
        return "echo-again";
    }

    /**
     * {@code HEAD}-only operation; {@code HEAD} is never implicit for a {@code GET}-only route in
     * this framework, so a dedicated operation is required to exercise a {@code HEAD} request.
     *
     * @return a succeeded future
     */
    @HEAD
    @Path("/head")
    public Future<Void> head() {
        return Future.succeededFuture();
    }

    /**
     * Operation that itself answers {@code 400}.
     *
     * @return a {@code 400} response with a fixed body
     */
    @GET
    @Path("/bad")
    @Produces(MediaType.TEXT_PLAIN)
    public Response bad() {
        return Response.status(400).entity("bad").build();
    }
}
