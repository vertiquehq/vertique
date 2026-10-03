// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource whose one operation, {@code GET /{a}/{b}/{c}}, matches every three-segment path, such as
 * a document URL {@code /apidocs/api/openapi.json} under a root mount. It answers with
 * {@value #MARKER}.
 */
@Path("/{a}/{b}/{c}")
public class ThreeSegmentsResource extends CountingResource {

    /** The literal body the resource answers with. */
    public static final String MARKER = "three-segments";

    /** Creates the resource with no answered request. */
    public ThreeSegmentsResource() {
        super(MARKER);
    }

    /**
     * Answers with {@value #MARKER}.
     *
     * @param a the first segment
     * @param b the second segment
     * @param c the third segment
     * @return the marker
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String threeSegments(@PathParam("a") String a, @PathParam("b") String b, @PathParam("c") String c) {
        return answer();
    }
}
