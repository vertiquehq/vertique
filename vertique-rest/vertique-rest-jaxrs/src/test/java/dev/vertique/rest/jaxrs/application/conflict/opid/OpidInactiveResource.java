// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Case (e)'s placeholder listed class for {@code OpidApis.OpidInactiveApi}: never resolved, since
 * an inactive registration's membership is never evaluated (AC-026.2).
 */
@Path("/opid-inactive")
public class OpidInactiveResource {

    /** Public no-arg constructor; never invoked. */
    public OpidInactiveResource() {}

    /**
     * Never reachable.
     *
     * @return the fixed body {@code "list"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "list";
    }
}
