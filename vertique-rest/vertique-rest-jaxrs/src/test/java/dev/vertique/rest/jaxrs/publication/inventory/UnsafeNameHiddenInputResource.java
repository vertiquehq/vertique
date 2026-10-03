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
 * Resource whose one operation declares a hidden method-level entry, with no location, whose name
 * matches no input, carries a control character, and is 300 characters long. Used only by the
 * failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/unsafe-hidden")
public class UnsafeNameHiddenInputResource {

    private static final String TEN_A = "aaaaaaaaaa";
    private static final String TEN_Z = "zzzzzzzzzz";

    /**
     * The entry's name: {@code bell}, the BEL control character (octal 007), 123 {@code a}, the
     * marker {@code TAIL}, and 168 {@code z}; 300 characters in all, the marker starting at index 128.
     */
    public static final String NAME = "bell" + "\007"
            + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + TEN_A + "aaa"
            + "TAIL"
            + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z
            + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + TEN_Z + "zzzzzzzz";

    /**
     * {@code GET /unsafe-hidden}.
     *
     * @param present query {@code present}; the hidden entry names no input (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = NAME, hidden = true)
    public String hideUnsafeName(@QueryParam("present") String present) {
        return "unsafe";
    }
}
