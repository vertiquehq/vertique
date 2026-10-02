// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The resource of the application {@code orders}: {@code GET /items} ({@value #LIST_ITEMS}). */
@Path(OrderResource.ROUTE)
public class OrderResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/items";

    /** The operation id of {@link #listItems}. */
    public static final String LIST_ITEMS = "listItems";

    /** The fixed JSON body {@link #listItems} returns. */
    public static final String BODY = "[]";

    /** Creates the resource. */
    public OrderResource() {}

    /**
     * Handles {@code GET /items}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String listItems() {
        return BODY;
    }
}
