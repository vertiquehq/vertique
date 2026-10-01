// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource whose one operation hides {@code x-trace}, with no location, while binding header {@code
 * X-Trace} and query {@code page}: the entry differs from the header only in ASCII letter case, and a
 * header binding matches ignoring ASCII letter case whatever location the entry names, so the entry
 * hides the header and the query input stays visible.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/default-location-case-folded-hidden")
public class DefaultLocationCaseFoldedHiddenHeaderResource {

    /**
     * {@code GET /default-location-case-folded-hidden}.
     *
     * @param trace header {@code X-Trace}, hidden by the method's entry {@code x-trace} (index 0)
     * @param page  query {@code page}, not hidden (index 1)
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Parameter(name = "x-trace", hidden = true)
    public String hideTraceByOtherCase(@HeaderParam("X-Trace") String trace, @QueryParam("page") String page) {
        return "folded";
    }
}
