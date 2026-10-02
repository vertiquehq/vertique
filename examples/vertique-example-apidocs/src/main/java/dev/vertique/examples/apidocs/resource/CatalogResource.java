// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;

/**
 * The public catalog: anyone may list and read the published items.
 *
 * <p>The catalog is a fixed in-memory list, enough to give the published document real operations.
 *
 * <p>The catalog is public on purpose: {@link PermitAll} states that policy explicitly, because the
 * application has authentication configured.
 */
@PermitAll
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

    private static final List<CatalogItem> ITEMS = List.of(
            new CatalogItem("1", "Espresso cup"), new CatalogItem("2", "Milk jug"), new CatalogItem("3", "Tamper"));

    /** Creates the resource. */
    @Inject
    public CatalogResource() {}

    /**
     * Lists the published items, optionally only those whose name contains a fragment.
     *
     * @param q a case-insensitive fragment of the item name, or {@code null} for every item
     * @return the matching items, ordered by identifier
     */
    @GET
    @Operation(summary = "List catalog items", description = "Returns the published items.")
    public List<CatalogItem> listItems(
            @QueryParam("q") @Parameter(description = "Case-insensitive fragment of the item name") String q) {
        if (q == null || q.isBlank()) {
            return ITEMS;
        }
        String fragment = q.toLowerCase(Locale.ROOT);
        return ITEMS.stream()
                .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(fragment))
                .toList();
    }

    /**
     * Returns one published item.
     *
     * @param id the public item identifier
     * @return the item
     * @throws NotFoundException when no published item has the identifier
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Find a catalog item", description = "Returns one published item.")
    @ApiResponse(responseCode = "200", description = "The catalog item")
    public CatalogItem getItem(@PathParam("id") @Parameter(description = "Public item identifier") String id) {
        return ITEMS.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(NotFoundException::new);
    }
}
