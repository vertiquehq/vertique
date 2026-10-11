// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.gip.chain;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** A secured route declared package-private: the first link of a package-private override chain. */
public class ChainBase {

    @GET
    @Path("/{id}")
    @RolesAllowed("admin")
    String find(@PathParam("id") String id) {
        return "base " + id;
    }
}
