// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 21's substituted subclass: what {@link Case21SubstitutionModule} actually returns for
 * a {@code Provider<Case21Resource>} request. Adds a new resource method carrying a runtime-
 * retained JAX-RS annotation, so {@code sameSurface(Case21Resource.class, this.getClass())} fails
 * at C-COMPOSE step 8's catalog instance check.
 */
public class Case21SubclassResource extends Case21Resource {

    /** Public no-arg constructor. */
    public Case21SubclassResource() {}

    /**
     * Handles {@code GET /case21-extra}; the added resource method that trips {@code sameSurface}.
     *
     * @return the fixed body {@code "case21Extra"}
     */
    @GET
    @Path("/case21-extra")
    @Produces(MediaType.TEXT_PLAIN)
    public String case21Extra() {
        return "case21Extra";
    }
}
