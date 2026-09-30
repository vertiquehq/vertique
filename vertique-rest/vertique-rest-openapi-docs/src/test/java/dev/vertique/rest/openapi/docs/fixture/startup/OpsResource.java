// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The resource of {@link OpsApi}: one operation, {@code GET /ops}. */
@Path("/ops")
public class OpsResource {

    /** The operation id of {@link #getOpsStatus}. */
    public static final String GET_OPS_STATUS = "getOpsStatus";

    /** The fixed body {@link #getOpsStatus} returns. */
    public static final String BODY = "ops";

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpsResource() {}

    /**
     * Handles {@code GET /ops}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getOpsStatus() {
        return BODY;
    }
}
