// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 22's unrelated type: not a subtype of {@link Case22Resource}, but is exactly what
 * {@link Case22HandWrittenEntryModule}'s hand-written entry returns for a {@link Case22Resource}
 * catalog entry, tripping C-COMPOSE step 8's catalog instance check.
 */
@Path("/case22-unrelated")
public class Case22UnrelatedResource {

    /** Public no-arg constructor, called directly by {@link Case22HandWrittenEntryModule} via {@code new}. */
    public Case22UnrelatedResource() {}

    /**
     * Handles {@code GET /case22-unrelated}.
     *
     * @return the fixed body {@code "case22Unrelated"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String case22Unrelated() {
        return "case22Unrelated";
    }
}
