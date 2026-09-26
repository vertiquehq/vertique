// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link OtherMountModule}'s hand-built
 * {@code /other/*} mount (case (f)), beside the reused
 * {@link dev.vertique.rest.jaxrs.application.conflict.paths.RootApplication RootApplication} at
 * the root path: {@code /} conflicts with every mount. Its resource method's name is unique
 * across every T004 conflict fixture.
 */
@Path("/other-probe")
public class OtherResource {

    /**
     * Handles {@code GET /other-probe}.
     *
     * @return the fixed body {@code "other"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltOther() {
        return "other";
    }
}
