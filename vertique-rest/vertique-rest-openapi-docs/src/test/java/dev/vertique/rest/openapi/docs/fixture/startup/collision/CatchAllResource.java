// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /{path: .*}}: a catch-all route that answers every path under its
 * mount, whatever the documentation prefix.
 */
@Path("/{path: .*}")
public class CatchAllResource extends CaseResource {

    /** The literal body {@link #catchAll} answers with. */
    public static final String MARKER = "case-catch-all";

    /** Creates the resource with no answered request. */
    public CatchAllResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{path: .*}}.
     *
     * @param path the whole mount-relative path
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String catchAll(@PathParam("path") String path) {
        return answer();
    }
}
