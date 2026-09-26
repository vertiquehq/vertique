// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link PublicityMountModule}'s hand-built
 * {@code /api/publicity/*} mount (case (b), the control), which does NOT conflict with
 * {@code PublicApplication}'s {@code /api/public/*} mount: neither {@code /api/public/} nor
 * {@code /api/publicity/} is a prefix of the other. Its resource method's name is unique across
 * every T004 conflict fixture.
 */
@Path("/publicity-probe")
public class PublicityResource {

    /**
     * Handles {@code GET /publicity-probe}.
     *
     * @return the fixed body {@code "publicity"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltPublicityItem() {
        return "publicity";
    }
}
