// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides {@code ghost}, with no location, while binding no input of that
 * name anywhere. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/unmatched-hidden")
public class UnmatchedHiddenInputResource {

    /**
     * {@code GET /unmatched-hidden}.
     *
     * @param present query {@code present}; no input is named {@code ghost} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "ghost", hidden = true)
    public String hideGhost(@QueryParam("present") String present) {
        return "unmatched";
    }
}
