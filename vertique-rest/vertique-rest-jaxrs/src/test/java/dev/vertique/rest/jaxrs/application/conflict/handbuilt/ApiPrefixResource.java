// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link ApiPrefixMountModule}'s hand-built
 * {@code /api/*} mount (case (a)), which conflicts with {@code ManagementApplication}'s
 * {@code /api/mgmt/*} mount, since {@code /api/} is a prefix of {@code /api/mgmt/}. Its resource
 * method's name is unique across every T004 conflict fixture, so it can never contribute an
 * unintended cross-mount operationId collision (FR-014).
 */
@Path("/api-prefix-probe")
public class ApiPrefixResource {

    /**
     * Handles {@code GET /api-prefix-probe}.
     *
     * @return the fixed body {@code "api-prefix"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltApiPrefix() {
        return "api-prefix";
    }
}
