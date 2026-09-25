// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 (T004) case (b) fixture: one resource, listed by both {@link OpidShareOneApplication} and
 * {@link OpidShareTwoApplication}. C-COMPOSE step 6.6 resolves this catalog entry once and shares
 * the same instance across both mounts, so the {@link #list()} operation each mount exposes has
 * the same owner (the same resource class, method name, and parameter types) — the cross-mount
 * operationId collision must not fail deployment.
 */
@Path("/opid-shared")
public class OpidSharedListResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidSharedListResource() {}

    /**
     * Handles {@code GET /opid-shared}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "opid-shared"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "opid-shared";
    }
}
