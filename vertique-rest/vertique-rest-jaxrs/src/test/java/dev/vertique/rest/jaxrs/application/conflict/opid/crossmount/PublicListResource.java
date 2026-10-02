// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.crossmount;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-009's {@code public} registration's sole listed resource (T023 contract, TP-009). Its
 * {@link #list()} method's default operationId ({@code "list"}) collides with
 * {@link PartnerListResource#list()}'s, but the two resource classes are unrelated, so they have no
 * common owner: the global cross-mount operationId refusal (rest-024 FR-014) must still reject the
 * collision under every strategy and contract-location row TP-009 adds, since FR-027's per-mount
 * relaxation is staged to CO-4 and not delivered by this task (CX-007).
 */
@Path("/cross-mount-public-list")
public class PublicListResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public PublicListResource() {}

    /**
     * Handles {@code GET /cross-mount-public-list}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "cross-mount-public-list"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "cross-mount-public-list";
    }
}
