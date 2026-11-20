// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource two applications share: {@code GET /items}, operation {@code listItems}. Mounted by
 * both {@code public} and {@code partner}, it yields the same route template and operationId on
 * each mount, so only the {@code rest.application} tag separates their series.
 */
@Path("/items")
public class SharedItemsResource {

    /** Constructed by Dagger through its catalog entry. */
    @Inject
    public SharedItemsResource() {}

    /**
     * Handles {@code GET /items}.
     *
     * @return the fixed body {@code "items"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listItems() {
        return "items";
    }
}
