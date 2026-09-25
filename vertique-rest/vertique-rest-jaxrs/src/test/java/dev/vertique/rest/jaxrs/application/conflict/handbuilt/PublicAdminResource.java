// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link PublicAdminMountModule}'s hand-built
 * {@code /api/public/admin/*} mount (case (e)), nested INSIDE {@code PublicApplication}'s
 * {@code /api/public/*} mount: the application's prefix, {@code /api/public/}, is a prefix of the
 * hand-built mount's prefix, {@code /api/public/admin/}, the reverse direction from case (a). Its
 * resource method's name is unique across every T004 conflict fixture.
 */
@Path("/public-admin-probe")
public class PublicAdminResource {

    /**
     * Handles {@code GET /public-admin-probe}.
     *
     * @return the fixed body {@code "public-admin"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltPublicAdmin() {
        return "public-admin";
    }
}
