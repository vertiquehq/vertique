// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (a) fixture: {@link OpidAlphaApplication}'s sole listed resource, at its own
 * {@code @Path}. Its {@link #list()} method's default operationId ({@code "list"}) collides with
 * {@link OpidBetaListResource#list()}'s, but the two resource classes are unrelated (neither is
 * the other's superclass), so they have no common owner — the collision must fail deployment.
 */
@Path("/opid-alpha")
public class OpidAlphaListResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidAlphaListResource() {}

    /**
     * Handles {@code GET /opid-alpha}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-alpha"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-alpha";
    }
}
