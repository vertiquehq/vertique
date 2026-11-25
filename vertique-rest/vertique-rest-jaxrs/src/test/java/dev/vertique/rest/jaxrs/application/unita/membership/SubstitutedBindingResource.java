// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Cataloged resource type. Deliberately carries <strong>no</strong>
 * {@code @Inject} constructor, so Dagger cannot auto-construct it: the only binding for
 * {@code Provider<SubstitutedBindingResource>} comes from {@link BindingSubstitutionModule}, whose
 * {@code @Provides} method returns a {@link SubstitutedBindingSubclassResource} instance instead — the
 * substitution {@link SubstitutedBindingCatalogModule}'s catalog entry resolves during composition, tripping
 * the catalog instance check ({@code sameSurface(entry.type(), r.getClass())}).
 */
@Path("/substituted-binding")
public class SubstitutedBindingResource {

    /** Public no-arg constructor, called directly by {@link BindingSubstitutionModule} via {@code new}. */
    public SubstitutedBindingResource() {}

    /**
     * Handles {@code GET /substituted-binding}.
     *
     * @return the fixed body {@code "substitutedBinding"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String substitutedBinding() {
        return "substitutedBinding";
    }
}
