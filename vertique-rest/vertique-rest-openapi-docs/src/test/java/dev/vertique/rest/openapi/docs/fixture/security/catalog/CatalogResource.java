// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Open to every caller: {@code @PermitAll}, no security requirement, and no required action, so it
 * does not restrict callers.
 */
@Path("/catalog")
@PermitAll
public class CatalogResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public CatalogResource() {}

    /**
     * Handles {@code GET /catalog}.
     *
     * @return the fixed body {@code "catalog"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listCatalog() {
        return "catalog";
    }
}
