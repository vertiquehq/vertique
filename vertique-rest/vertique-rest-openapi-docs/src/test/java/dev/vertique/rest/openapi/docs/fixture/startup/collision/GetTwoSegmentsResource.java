// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The case resource of {@code GET /{a}/{b}}: two path segments. */
@Path("/{a}/{b}")
public class GetTwoSegmentsResource extends CaseResource {

    /** The literal body {@link #getTwoSegments} answers with. */
    public static final String MARKER = "case-get-two-segments";

    /** Creates the resource with no answered request. */
    public GetTwoSegmentsResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{a}/{b}}.
     *
     * @param a the first segment
     * @param b the second segment
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getTwoSegments(@PathParam("a") String a, @PathParam("b") String b) {
        return answer();
    }
}
