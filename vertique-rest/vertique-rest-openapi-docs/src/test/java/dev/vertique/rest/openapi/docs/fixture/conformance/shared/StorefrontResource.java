// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link StorefrontApi}: two unguarded operations under {@code /entries}, with a
 * query, a header, and a path parameter, a plain-text response, and an inferred JSON response beside
 * a declared {@code 404}.
 */
@Tag(name = StorefrontResource.TAG)
@Path("/entries")
public class StorefrontResource {

    /** The tag the resource carries. */
    public static final String TAG = "storefront";

    /** The operation id of {@link #listEntries}. */
    public static final String LIST_ENTRIES = "listEntries";

    /** The operation id of {@link #getEntry}. */
    public static final String GET_ENTRY = "getEntry";

    /** The fixed body {@link #listEntries} returns. */
    public static final String BODY = "entries";

    /** Creates the resource. */
    public StorefrontResource() {}

    /**
     * Handles {@code GET /entries}.
     *
     * @param limit  the maximum number of entries, or {@code null} when absent
     * @param locale the caller's locale, or {@code null} when absent
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = LIST_ENTRIES, summary = "List entries")
    public String listEntries(@QueryParam("limit") Integer limit, @HeaderParam("X-Locale") String locale) {
        return BODY;
    }

    /**
     * Handles {@code GET /entries/{id}}.
     *
     * @param id the entry id
     * @return the entry
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = GET_ENTRY, summary = "Get an entry")
    @ApiResponse(responseCode = "200", description = "The entry")
    @ApiResponse(responseCode = "404", description = "No such entry")
    public Future<EntryView> getEntry(@PathParam("id") String id) {
        return Future.succeededFuture(new EntryView(id, "Entry " + id));
    }
}
