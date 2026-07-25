// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** Identical source compiled through the public parent and the legacy explicit processor paths. */
@Path("/parity")
public final class ParityApp {

    @GET
    @Path("/{value}")
    public String get(@PathParam("value") String value) {
        return value;
    }
}
