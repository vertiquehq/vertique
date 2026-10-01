// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The case resource of {@code GET /{a}/{b}/{c}}: three path segments, as many as a document URL has. */
@Path("/{a}/{b}/{c}")
public class GetThreeSegmentsResource extends CaseResource {

    /** The literal body {@link #getThreeSegments} answers with. */
    public static final String MARKER = "case-get-three-segments";

    /** Creates the resource with no answered request. */
    public GetThreeSegmentsResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{a}/{b}/{c}}.
     *
     * @param a the first segment
     * @param b the second segment
     * @param c the third segment
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getThreeSegments(@PathParam("a") String a, @PathParam("b") String b, @PathParam("c") String c) {
        return answer();
    }
}
