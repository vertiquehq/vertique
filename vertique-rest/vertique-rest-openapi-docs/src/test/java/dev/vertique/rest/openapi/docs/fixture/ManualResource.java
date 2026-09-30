// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The sole resource of the hand-built manual mount at {@value ManualMountModule#MOUNT_PATH}. It is
 * never a {@code @JaxRsResources} contribution, so no application selects it, and its operation id
 * differs from every application operation's.
 */
@Path("/ping")
public class ManualResource {

    /** The operation id of {@link #manualPing}. */
    public static final String MANUAL_PING = "manualPing";

    /** The fixed body {@link #manualPing} returns. */
    public static final String BODY = "pong";

    /** Creates the resource. */
    public ManualResource() {}

    /**
     * Handles {@code GET /ping}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String manualPing() {
        return BODY;
    }
}
