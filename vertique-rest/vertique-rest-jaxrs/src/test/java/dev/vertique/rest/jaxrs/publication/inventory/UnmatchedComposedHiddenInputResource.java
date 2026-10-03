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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resource whose one operation carries a composed annotation that hides query {@code phantom}, while
 * the method binds no input of that name. Used only by the failing-mount proof.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/unmatched-composed-hidden")
public class UnmatchedComposedHiddenInputResource {

    /** Composed annotation carrying {@code @Parameter(name = "phantom", in = QUERY, hidden = true)}. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Parameter(name = "phantom", in = ParameterIn.QUERY, hidden = true)
    public @interface HiddenPhantom {}

    /**
     * {@code GET /unmatched-composed-hidden}.
     *
     * @param present query {@code present}; no input is named {@code phantom} (index 0)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @HiddenPhantom
    public String hidePhantom(@QueryParam("present") String present) {
        return "unmatched";
    }
}
