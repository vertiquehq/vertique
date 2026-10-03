// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Reflection-path resource whose one operation declares a {@code @NotNull} whose group the proof
 * makes unloadable, beside a {@code @NotNull} in {@code Default}. The proof defines this class in a
 * class loader that cannot load {@link UnloadableGroup}, so the class must stay public and
 * self-contained: it touches no package-private member of this package. It is a reflection-path
 * fixture: no generated companion exists for it, and none may be added.
 */
@Path("/unreadable-groups")
public class UnreadableGroupsResource {

    /**
     * {@code GET /unreadable-groups}, declaring no validation groups.
     *
     * @param zone {@code @NotNull} in {@link UnloadableGroup}, whose groups cannot be read
     * @param area {@code @NotNull} in {@code Default}, named explicitly
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listUnreadableGroups(
            @QueryParam("zone") @NotNull(groups = UnloadableGroup.class) String zone,
            @QueryParam("area") @NotNull(groups = Default.class) String area) {
        return "unreadable groups";
    }
}
