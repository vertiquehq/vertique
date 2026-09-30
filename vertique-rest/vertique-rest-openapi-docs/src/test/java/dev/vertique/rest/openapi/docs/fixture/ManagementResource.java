// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The resource of {@link MgmtApi} and {@link ProtectedMgmtApi}: one operation, {@code GET /status}. */
@Path("/status")
public class ManagementResource {

    /** The operation id of {@link #getStatus}. */
    public static final String GET_STATUS = "getStatus";

    /** The fixed body {@link #getStatus} returns. */
    public static final String BODY = "ok";

    /** Public {@code @Inject} constructor. */
    @Inject
    public ManagementResource() {}

    /**
     * Handles {@code GET /status}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getStatus() {
        return BODY;
    }
}
