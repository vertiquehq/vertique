// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The case resource of {@code GET /{id}}: one path segment. */
@Path("/{id}")
public class GetIdResource extends CaseResource {

    /** The literal body {@link #getId} answers with. */
    public static final String MARKER = "case-get-id";

    /** Creates the resource with no answered request. */
    public GetIdResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{id}}.
     *
     * @param id the segment
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getId(@PathParam("id") String id) {
        return answer();
    }
}
