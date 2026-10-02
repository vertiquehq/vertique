// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource of the application {@code alpha}: {@code GET /catalog} ({@value #OPERATION_ID}). */
@Path(AlphaCatalogResource.ROUTE)
public class AlphaCatalogResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/catalog";

    /** The operation id of {@link #listCatalog}. */
    public static final String OPERATION_ID = "listCatalog";

    /** The fixed body the resource answers with. */
    public static final String BODY = "listCatalog";

    /** Creates the resource. */
    public AlphaCatalogResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listCatalog() {
        return BODY;
    }
}
