// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T005 TP-002 case (a): an entirely unannotated JAX-RS resource — no {@code @PermitAll}, no
 * {@code @SecurityRequirement}, no {@code @RequiresAction} — with two operations, so both are
 * implicit under C-POLICY (effective policy {@code None}, no security requirement sets, no
 * resolved required action). {@link #mgmtItems()} and {@link #mgmtStatus()} are named after the
 * contract's example method names; their operationIds default to those method names, since neither
 * carries a Swagger {@code @Operation} annotation.
 */
@Path("/console")
public class UnannotatedResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public UnannotatedResource() {}

    /**
     * Handles {@code GET .../console/items}.
     *
     * @return the fixed body {@code "items"}
     */
    @GET
    @Path("/items")
    @Produces(MediaType.TEXT_PLAIN)
    public String mgmtItems() {
        return "items";
    }

    /**
     * Handles {@code GET .../console/status}.
     *
     * @return the fixed body {@code "status"}
     */
    @GET
    @Path("/status")
    @Produces(MediaType.TEXT_PLAIN)
    public String mgmtStatus() {
        return "status";
    }
}
