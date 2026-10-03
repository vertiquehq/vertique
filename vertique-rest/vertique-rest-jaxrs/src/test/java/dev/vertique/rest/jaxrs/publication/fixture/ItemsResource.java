// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.security.authz.RequiresAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T006 TP-002's fixture resource: a plain listing, a plain path-param lookup, a
 * regex-constrained path-param lookup, a {@code HEAD} sibling of the plain lookup, a restricted
 * write declaring both {@code @RolesAllowed} and a scoped {@code @SecurityRequirement}, and an
 * action-gated read.
 */
@Path("/items")
public class ItemsResource {

    /** The canonical action this resource's admin-view operation requires. */
    public static final String ADMIN_VIEW_ACTION = "items.admin.view";

    /**
     * Lists items.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "list";
    }

    /**
     * Reads one item by its unconstrained path id.
     *
     * @param id the path id
     * @return the id, echoed
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getById(@PathParam("id") String id) {
        return "item:" + id;
    }

    /**
     * Reads one item's detail by a digits-only path id.
     *
     * @param id the digits-only path id
     * @return the id, echoed
     */
    @GET
    @Path("/{id: [0-9]+}/detail")
    @Produces(MediaType.TEXT_PLAIN)
    public String getDetail(@PathParam("id") String id) {
        return "detail:" + id;
    }

    /**
     * {@code HEAD} sibling of {@link #getById(String)}.
     *
     * @param id the path id (unused; {@code HEAD} carries no body)
     * @return a succeeded future
     */
    @HEAD
    @Path("/{id}")
    public Future<Void> headById(@PathParam("id") String id) {
        return Future.succeededFuture();
    }

    /**
     * Creates an item; restricted to the {@code admin} role and the {@code bearerAuth} scheme with
     * the {@code items:write} scope.
     *
     * @return a fixed body
     */
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("admin")
    @Operation(security = @SecurityRequirement(name = "bearerAuth", scopes = "items:write"))
    public String create() {
        return "created";
    }

    /**
     * Reads one item's admin view; gated by {@link #ADMIN_VIEW_ACTION}.
     *
     * @param id the path id
     * @return the id, echoed
     */
    @GET
    @Path("/{id}/admin")
    @Produces(MediaType.TEXT_PLAIN)
    @RequiresAction(ADMIN_VIEW_ACTION)
    public String adminView(@PathParam("id") String id) {
        return "admin:" + id;
    }
}
