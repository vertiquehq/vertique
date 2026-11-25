// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Listed type with a hand-written catalog entry. {@link HandWrittenCatalogEntryModule} catalogs
 * this class but its provider returns a {@link UnrelatedEntryResource} instance, tripping
 * the composer's catalog instance check ({@code sameSurface(entry.type(), r.getClass())}).
 */
@Path("/hand-written-entry")
public class HandWrittenEntryResource {

    /** Public no-arg constructor; never actually invoked (the entry's provider returns a different type). */
    public HandWrittenEntryResource() {}

    /**
     * Handles {@code GET /hand-written-entry}.
     *
     * @return the fixed body {@code "handWrittenEntry"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handWrittenEntry() {
        return "handWrittenEntry";
    }
}
