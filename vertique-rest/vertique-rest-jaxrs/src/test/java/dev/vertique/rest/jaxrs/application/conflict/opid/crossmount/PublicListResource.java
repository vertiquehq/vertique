// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.crossmount;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The {@code public} registration's sole listed resource. Its {@link #list()} method's default
 * operationId ({@code "list"}) is shared with {@link PartnerListResource#list()}'s, but the two
 * resource classes are unrelated, so they have no common owner: the pair deploys unless both mounts
 * resolve to one contract location under a contract-driven strategy.
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
