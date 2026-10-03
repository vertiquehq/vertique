// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Resource with one operation whose only input is an unconstrained request body. */
@Path("/orders")
public class BodyOnlyResource {

    /**
     * {@code POST /orders} with an {@link Order} body.
     *
     * @param order the request body
     * @return a fixed body
     */
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public String submitOrder(Order order) {
        return "submitted";
    }
}
