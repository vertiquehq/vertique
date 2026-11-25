// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Substituted subclass: what {@link BindingSubstitutionModule} actually returns for
 * a {@code Provider<SubstitutedBindingResource>} request. Adds a new resource method carrying a runtime-
 * retained JAX-RS annotation, so {@code sameSurface(SubstitutedBindingResource.class, this.getClass())} fails
 * at the composer's catalog instance check.
 */
public class SubstitutedBindingSubclassResource extends SubstitutedBindingResource {

    /** Public no-arg constructor. */
    public SubstitutedBindingSubclassResource() {}

    /**
     * Handles {@code GET /substituted-binding-extra}; the added resource method that trips {@code sameSurface}.
     *
     * @return the fixed body {@code "substitutedBindingExtra"}
     */
    @GET
    @Path("/substituted-binding-extra")
    @Produces(MediaType.TEXT_PLAIN)
    public String substitutedBindingExtra() {
        return "substitutedBindingExtra";
    }
}
