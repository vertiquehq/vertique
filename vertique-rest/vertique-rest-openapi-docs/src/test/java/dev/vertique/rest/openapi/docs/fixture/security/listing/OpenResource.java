// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource {@code /open} of {@link RosterApi}: one operation, {@code GET} ({@value #OPEN_PING}),
 * open to every caller: {@code @PermitAll}, no security requirement, and no required action.
 */
@Path("/open")
@PermitAll
public class OpenResource {

    /** The operation id of {@link #openPing}, which is its method name. */
    public static final String OPEN_PING = "openPing";

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpenResource() {}

    /**
     * Handles {@code GET /open}.
     *
     * @return the fixed body {@code "open"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String openPing() {
        return "open";
    }
}
