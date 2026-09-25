// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-005 case 21's cataloged resource type. Deliberately carries <strong>no</strong>
 * {@code @Inject} constructor, so Dagger cannot auto-construct it: the only binding for
 * {@code Provider<Case21Resource>} comes from {@link Case21SubstitutionModule}, whose
 * {@code @Provides} method returns a {@link Case21SubclassResource} instance instead — the
 * substitution {@link Case21CatalogModule}'s catalog entry resolves at C-COMPOSE step 8, tripping
 * the catalog instance check ({@code sameSurface(entry.type(), r.getClass())}).
 */
@Path("/case21")
public class Case21Resource {

    /** Public no-arg constructor, called directly by {@link Case21SubstitutionModule} via {@code new}. */
    public Case21Resource() {}

    /**
     * Handles {@code GET /case21}.
     *
     * @return the fixed body {@code "case21"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String case21() {
        return "case21";
    }
}
