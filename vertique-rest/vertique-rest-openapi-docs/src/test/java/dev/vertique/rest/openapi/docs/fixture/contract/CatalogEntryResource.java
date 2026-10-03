// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of the application {@code catalog}: {@code GET /entries} ({@value #LIST_ENTRIES}), with
 * the query parameter {@value #LIMIT}. Its operation id differs from every other fixture operation's.
 */
@Path(CatalogEntryResource.ROUTE)
public class CatalogEntryResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/entries";

    /** The operation id of {@link #listEntries}. */
    public static final String LIST_ENTRIES = "listEntries";

    /** The query parameter of {@link #listEntries}. */
    public static final String LIMIT = "limit";

    /** The fixed body {@link #listEntries} returns. */
    public static final String BODY = "entries";

    /** Creates the resource. */
    public CatalogEntryResource() {}

    /**
     * Handles {@code GET /entries}.
     *
     * @param limit the maximum number of entries, or {@code null} when absent
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "List catalog entries")
    public String listEntries(@QueryParam(LIMIT) Integer limit) {
        return BODY;
    }
}
