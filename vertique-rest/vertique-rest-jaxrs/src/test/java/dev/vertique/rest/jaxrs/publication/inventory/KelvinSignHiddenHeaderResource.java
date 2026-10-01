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
 * Resource whose one operation hides a header entry equal to its bound header {@code X-Key} only
 * under Unicode case folding: the entry's {@code K} is U+212A KELVIN SIGN, which Unicode case mapping
 * folds to an ASCII {@code k} but an ASCII-only letter case comparison does not, so the hiding entry
 * matches no input. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/kelvin-sign-hidden")
public class KelvinSignHiddenHeaderResource {

    /** The name the hidden entry gives: {@code X-}, U+212A KELVIN SIGN, then {@code ey}. */
    public static final String ENTRY_NAME = "X-\u212Aey";

    /** The name the method binds. */
    public static final String BOUND_NAME = "X-Key";

    /** Creates the resource. */
    public KelvinSignHiddenHeaderResource() {}

    /**
     * {@code GET /kelvin-sign-hidden}.
     *
     * @param key header {@code X-Key}; the method's hidden entry names {@link #ENTRY_NAME} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = ENTRY_NAME, in = ParameterIn.HEADER, hidden = true)
    public String hideHeaderByKelvinSign(@HeaderParam(BOUND_NAME) String key) {
        return "kelvin";
    }
}
