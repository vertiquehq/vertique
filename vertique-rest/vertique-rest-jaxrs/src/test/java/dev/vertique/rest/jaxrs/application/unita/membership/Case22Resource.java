// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 22's listed, hand-written-entry type. {@link Case22HandWrittenEntryModule} catalogs
 * this class but its provider returns a {@link Case22UnrelatedResource} instance, tripping
 * C-COMPOSE step 8's catalog instance check ({@code sameSurface(entry.type(), r.getClass())}).
 */
@Path("/case22")
public class Case22Resource {

    /** Public no-arg constructor; never actually invoked (the entry's provider returns a different type). */
    public Case22Resource() {}

    /**
     * Handles {@code GET /case22}.
     *
     * @return the fixed body {@code "case22"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String case22() {
        return "case22";
    }
}
