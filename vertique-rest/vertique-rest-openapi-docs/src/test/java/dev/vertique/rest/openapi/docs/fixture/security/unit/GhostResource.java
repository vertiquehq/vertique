// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link GhostApi}: one operation, {@value #READ_GHOST}, that requires the scopeless
 * security scheme {@value #SCHEME} and nothing else.
 */
@Path("/ghost")
public class GhostResource {

    /** The security scheme {@link #readGhost} requires. */
    public static final String SCHEME = "ghostAuth";

    /** The operation id of {@link #readGhost}, which is its method name. */
    public static final String READ_GHOST = "readGhost";

    /** The path template of {@link #readGhost}. */
    public static final String READ_GHOST_PATH = "/ghost";

    /** The fixed body {@link #readGhost} returns. */
    public static final String BODY = "ghost";

    /** Public {@code @Inject} constructor. */
    @Inject
    public GhostResource() {}

    /**
     * Handles {@code GET /ghost}, requiring {@value #SCHEME}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = SCHEME)
    public String readGhost() {
        return BODY;
    }
}
