// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The resource of {@link DormantApi}: one operation, {@code GET /dormant}. */
@Path("/dormant")
public class DormantResource {

    /** The operation id of {@link #getDormantStatus}. */
    public static final String GET_DORMANT_STATUS = "getDormantStatus";

    /** The fixed body {@link #getDormantStatus} returns. */
    public static final String BODY = "dormant";

    /** Public {@code @Inject} constructor. */
    @Inject
    public DormantResource() {}

    /**
     * Handles {@code GET /dormant}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getDormantStatus() {
        return BODY;
    }
}
