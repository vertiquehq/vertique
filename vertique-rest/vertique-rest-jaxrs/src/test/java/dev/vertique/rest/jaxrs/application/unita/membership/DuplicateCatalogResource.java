// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource cataloged twice, by two separate modules
 * ({@link DuplicateCatalogEntryModuleA} and {@link DuplicateCatalogEntryModuleB}), so the composer's
 * duplicate-catalog-entry check ("two catalog entries of one class ... fail") has
 * something to catch, before any application is constructed.
 */
@Path("/duplicate-catalog")
public class DuplicateCatalogResource {

    /** Public {@code @Inject} constructor; never actually invoked (the duplicate-entry check fails first). */
    @Inject
    public DuplicateCatalogResource() {}

    /**
     * Handles {@code GET /duplicate-catalog}.
     *
     * @return the fixed body {@code "duplicateCatalog"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String duplicateCatalog() {
        return "duplicateCatalog";
    }
}
