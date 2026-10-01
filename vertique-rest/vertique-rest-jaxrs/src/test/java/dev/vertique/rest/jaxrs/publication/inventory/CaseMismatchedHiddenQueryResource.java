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
 * Resource whose one operation hides query {@code sortKey} while binding query {@code SortKey}: the
 * names differ only in case, and query names match exactly, so the hiding entry matches no input.
 * Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/case-mismatched-hidden-query")
public class CaseMismatchedHiddenQueryResource {

    /** The name the hidden entry gives. */
    public static final String ENTRY_NAME = "sortKey";

    /** The name the method binds. */
    public static final String BOUND_NAME = "SortKey";

    /** Creates the resource. */
    public CaseMismatchedHiddenQueryResource() {}

    /**
     * {@code GET /case-mismatched-hidden-query}.
     *
     * @param sort query {@code SortKey}; the method's hidden entry names {@code sortKey} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = ENTRY_NAME, in = ParameterIn.QUERY, hidden = true)
    public String hideQueryByOtherCase(@QueryParam(BOUND_NAME) String sort) {
        return "query";
    }
}
