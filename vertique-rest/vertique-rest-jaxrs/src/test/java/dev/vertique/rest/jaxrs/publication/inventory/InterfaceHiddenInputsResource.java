// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource implementing {@link InterfaceHiddenApi}: it declares the JAX-RS annotations, while the
 * hidden method-level {@code @Parameter} lives only on the interface method.
 *
 * <p>No generated descriptor companion exists for this class, and none may be added: it is a
 * reflection-path fixture, and it is not part of any parity mount.
 */
@Path("/interface-hidden")
public class InterfaceHiddenInputsResource implements InterfaceHiddenApi {

    /**
     * {@code GET /interface-hidden}.
     *
     * @param token header {@code X-Trace-Token}, hidden by the interface method (index 0)
     * @param page  query {@code page}, not hidden (index 1)
     * @return a fixed body
     */
    @Override
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String readTrace(@HeaderParam("X-Trace-Token") String token, @QueryParam("page") String page) {
        return "trace";
    }
}
