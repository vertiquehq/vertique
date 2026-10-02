// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.crossmount;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-009's {@code partner} registration's sole listed resource (T023 contract, TP-009). Its
 * {@link #list()} method's default operationId ({@code "list"}) collides with
 * {@link PublicListResource#list()}'s, but the two resource classes are unrelated, so they have no
 * common owner; see {@link PublicListResource}.
 */
@Path("/cross-mount-partner-list")
public class PartnerListResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public PartnerListResource() {}

    /**
     * Handles {@code GET /cross-mount-partner-list}, whose default operationId is {@code "list"}.
     *
     * @return the fixed body {@code "cross-mount-partner-list"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String list() {
        return "cross-mount-partner-list";
    }
}
