// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The resource of {@link LongNameApi}: one operation, {@code GET /long-name}. */
@Path("/long-name")
public class LongNameResource {

    /** The operation id of {@link #getLongNameStatus}. */
    public static final String GET_LONG_NAME_STATUS = "getLongNameStatus";

    /** The fixed body {@link #getLongNameStatus} returns. */
    public static final String BODY = "long-name";

    /** Public {@code @Inject} constructor. */
    @Inject
    public LongNameResource() {}

    /**
     * Handles {@code GET /long-name}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getLongNameStatus() {
        return BODY;
    }
}
