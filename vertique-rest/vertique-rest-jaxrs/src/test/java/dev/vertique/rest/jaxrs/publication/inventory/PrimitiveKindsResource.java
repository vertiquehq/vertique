// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Reflection-path resource whose one operation declares a primitive query parameter without a
 * default and a {@code @Valid} record composite with primitive and explicit-accessor members.
 * It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 */
@Path("/primitive-kinds")
public class PrimitiveKindsResource {

    /**
     * {@code GET /primitive-kinds}, declaring no validation groups.
     *
     * @param count  a primitive {@code int}, no default and no constraint
     * @param limits {@code @Valid} bean-param record composite
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listPrimitiveKinds(@QueryParam("count") int count, @BeanParam @Valid PrimitiveLimits limits) {
        return "primitive kinds";
    }
}
