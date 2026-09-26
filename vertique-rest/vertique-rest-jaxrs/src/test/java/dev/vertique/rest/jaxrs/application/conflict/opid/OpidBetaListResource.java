// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (a) fixture: {@link OpidBetaApplication}'s sole listed resource, at its own
 * {@code @Path}, unrelated to {@link OpidAlphaListResource}. Its {@link #list()} method's default
 * operationId ({@code "list"}) collides with {@link OpidAlphaListResource#list()}'s.
 */
@Path("/opid-beta")
public class OpidBetaListResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidBetaListResource() {}

    /**
     * Handles {@code GET /opid-beta}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-beta"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-beta";
    }
}
