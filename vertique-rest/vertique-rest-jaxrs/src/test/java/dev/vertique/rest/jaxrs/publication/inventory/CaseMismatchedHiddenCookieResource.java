// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides cookie {@code sessionTag} while binding cookie {@code
 * SessionTag}: the names differ only in case, and cookie names match exactly, so the hiding entry
 * matches no input. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/case-mismatched-hidden-cookie")
public class CaseMismatchedHiddenCookieResource {

    /** The name the hidden entry gives. */
    public static final String ENTRY_NAME = "sessionTag";

    /** The name the method binds. */
    public static final String BOUND_NAME = "SessionTag";

    /** Creates the resource. */
    public CaseMismatchedHiddenCookieResource() {}

    /**
     * {@code GET /case-mismatched-hidden-cookie}.
     *
     * @param tag cookie {@code SessionTag}; the method's hidden entry names {@code sessionTag} (index
     *            0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = ENTRY_NAME, in = ParameterIn.COOKIE, hidden = true)
    public String hideCookieByOtherCase(@CookieParam(BOUND_NAME) String tag) {
        return "cookie";
    }
}
