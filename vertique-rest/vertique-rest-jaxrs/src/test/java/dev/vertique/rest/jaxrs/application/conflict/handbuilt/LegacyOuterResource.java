// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link LegacyOuterMountModule}'s hand-built
 * {@code /api/legacy/*} mount (case (c)). Paired with {@link LegacyReportsResource}'s
 * {@code /api/legacy/reports/*} mount, with no application declared, so only
 * {@code HttpVerticle}'s existing containment-overlap warning applies, never the rest-jaxrs
 * validator's path-conflict rule (which requires at least one application mount). Its resource
 * method's name is unique across every T004 conflict fixture.
 */
@Path("/legacy-outer-probe")
public class LegacyOuterResource {

    /**
     * Handles {@code GET /legacy-outer-probe}.
     *
     * @return the fixed body {@code "legacy-outer"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltLegacyOuter() {
        return "legacy-outer";
    }
}
