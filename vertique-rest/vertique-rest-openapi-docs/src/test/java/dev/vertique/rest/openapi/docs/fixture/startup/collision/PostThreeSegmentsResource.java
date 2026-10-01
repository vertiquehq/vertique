// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The case resource of {@code POST /{a}/{b}/{c}}: a {@code POST}-only route that consumes no body. */
@Path("/{a}/{b}/{c}")
public class PostThreeSegmentsResource extends CaseResource {

    /** The literal body {@link #postThreeSegments} answers with. */
    public static final String MARKER = "case-post-three-segments";

    /** Creates the resource with no answered request. */
    public PostThreeSegmentsResource() {
        super(MARKER);
    }

    /**
     * Handles {@code POST /{a}/{b}/{c}}.
     *
     * @param a the first segment
     * @param b the second segment
     * @param c the third segment
     * @return the marker {@value #MARKER}
     */
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public String postThreeSegments(@PathParam("a") String a, @PathParam("b") String b, @PathParam("c") String c) {
        return answer();
    }
}
