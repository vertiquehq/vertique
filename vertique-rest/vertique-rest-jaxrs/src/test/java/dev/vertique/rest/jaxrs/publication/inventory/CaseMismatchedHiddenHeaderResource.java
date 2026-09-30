// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides header {@code x-debug-token} while binding header {@code
 * X-Debug-Token}: the names differ only in case, so the hiding entry matches no input. Used only by
 * the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/case-mismatched-hidden")
public class CaseMismatchedHiddenHeaderResource {

    /**
     * {@code GET /case-mismatched-hidden}.
     *
     * @param token header {@code X-Debug-Token}; the method's hidden entry names {@code
     *              x-debug-token} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "x-debug-token", in = ParameterIn.HEADER, hidden = true)
    public String hideHeaderByOtherCase(@HeaderParam("X-Debug-Token") String token) {
        return "case";
    }
}
