// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides a header {@code q} while binding {@code q} from the query only,
 * so the hiding entry matches no input. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/mismatched-hidden")
public class MismatchedHiddenInputResource {

    /**
     * {@code GET /mismatched-hidden}.
     *
     * @param q query {@code q}; the method's hidden entry names a header {@code q} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "q", in = ParameterIn.HEADER, hidden = true)
    public String hideQueryAsHeader(@QueryParam("q") String q) {
        return "mismatched";
    }
}
