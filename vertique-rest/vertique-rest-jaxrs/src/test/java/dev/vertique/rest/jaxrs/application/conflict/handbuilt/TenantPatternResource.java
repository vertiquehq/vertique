// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link TenantPatternMountModule}'s hand-built
 * {@code /:tenant/*} pattern-path mount (case (d)). C-CONFLICT treats a non-application JAX-RS
 * mount whose prefix contains a colon or curly brace as conflicting with every application mount,
 * regardless of any literal prefix relation to {@code PublicApplication}'s {@code /api/public/*}.
 * Its resource method's name is unique across every T004 conflict fixture.
 */
@Path("/tenant-pattern-probe")
public class TenantPatternResource {

    /**
     * Handles {@code GET /tenant-pattern-probe}.
     *
     * @return the fixed body {@code "tenant-pattern"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltTenantPattern() {
        return "tenant-pattern";
    }
}
