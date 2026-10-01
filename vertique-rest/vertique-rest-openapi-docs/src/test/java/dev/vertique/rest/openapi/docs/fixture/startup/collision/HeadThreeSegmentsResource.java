// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** The case resource of {@code HEAD /{a}/{b}/{c}}: a {@code HEAD}-only route, no {@code GET}. */
@Path("/{a}/{b}/{c}")
public class HeadThreeSegmentsResource extends CaseResource {

    /** The marker of the resource; a {@code HEAD} response carries no body, so only the count shows an answer. */
    public static final String MARKER = "case-head-three-segments";

    /** Creates the resource with no answered request. */
    public HeadThreeSegmentsResource() {
        super(MARKER);
    }

    /**
     * Handles {@code HEAD /{a}/{b}/{c}}.
     *
     * @param a the first segment
     * @param b the second segment
     * @param c the third segment
     */
    @HEAD
    public void headThreeSegments(@PathParam("a") String a, @PathParam("b") String b, @PathParam("c") String c) {
        answer();
    }
}
