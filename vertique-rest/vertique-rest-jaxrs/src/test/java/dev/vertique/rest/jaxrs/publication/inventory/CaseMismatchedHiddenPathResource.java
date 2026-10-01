// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides path {@code itemId} while binding path {@code ItemId}: the
 * names differ only in case, and path names match exactly, so the hiding entry matches no input.
 * Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/case-mismatched-hidden-path")
public class CaseMismatchedHiddenPathResource {

    /** The name the hidden entry gives. */
    public static final String ENTRY_NAME = "itemId";

    /** The name the method binds. */
    public static final String BOUND_NAME = "ItemId";

    /** Creates the resource. */
    public CaseMismatchedHiddenPathResource() {}

    /**
     * {@code GET /case-mismatched-hidden-path/{ItemId}}.
     *
     * @param item path {@code ItemId}; the method's hidden entry names {@code itemId} (index 0)
     * @return a fixed body
     */
    @GET
    @Path("/{" + BOUND_NAME + "}")
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = ENTRY_NAME, in = ParameterIn.PATH, hidden = true)
    public String hidePathByOtherCase(@PathParam(BOUND_NAME) String item) {
        return "path";
    }
}
