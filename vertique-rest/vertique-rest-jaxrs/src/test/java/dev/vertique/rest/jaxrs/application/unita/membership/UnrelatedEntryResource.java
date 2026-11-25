// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Unrelated type: not a subtype of {@link HandWrittenEntryResource}, but is exactly what
 * {@link HandWrittenCatalogEntryModule}'s hand-written entry returns for a {@link HandWrittenEntryResource}
 * catalog entry, tripping the composer's catalog instance check.
 */
@Path("/unrelated-entry")
public class UnrelatedEntryResource {

    /** Public no-arg constructor, called directly by {@link HandWrittenCatalogEntryModule} via {@code new}. */
    public UnrelatedEntryResource() {}

    /**
     * Handles {@code GET /unrelated-entry}.
     *
     * @return the fixed body {@code "unrelatedEntry"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String unrelatedEntry() {
        return "unrelatedEntry";
    }
}
