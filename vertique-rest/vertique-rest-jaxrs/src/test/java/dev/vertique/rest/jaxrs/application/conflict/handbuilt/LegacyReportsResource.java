// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * TP-004 (T004) fixture: the sole resource of {@link LegacyReportsMountModule}'s hand-built
 * {@code /api/legacy/reports/*} mount (case (c)), nested inside {@link LegacyOuterResource}'s
 * {@code /api/legacy/*} mount. Its resource method's name is unique across every T004 conflict
 * fixture.
 */
@Path("/legacy-reports-probe")
public class LegacyReportsResource {

    /**
     * Handles {@code GET /legacy-reports-probe}.
     *
     * @return the fixed body {@code "legacy-reports"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String handBuiltLegacyReports() {
        return "legacy-reports";
    }
}
