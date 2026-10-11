// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.gip.chain;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * A generic base class with a secured package-private route.
 *
 * @param <T> the identifier type
 */
public class RelayBase<T> {

    @GET
    @Path("/{id}")
    @RolesAllowed("admin")
    String get(@PathParam("id") T id) {
        return "base " + id;
    }
}
