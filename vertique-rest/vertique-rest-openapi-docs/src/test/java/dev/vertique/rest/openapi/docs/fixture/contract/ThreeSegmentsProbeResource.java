// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource whose one operation, {@code GET /{a}/{b}/{c}} ({@value #OPERATION_ID}), has three path
 * segments, as many as a document URL has below its documentation prefix.
 */
@Path(ThreeSegmentsProbeResource.ROUTE)
public class ThreeSegmentsProbeResource {

    /** The resource path, relative to its mount. */
    public static final String ROUTE = "/{a}/{b}/{c}";

    /** The operation id of {@link #threeSegmentsProbe}. */
    public static final String OPERATION_ID = "threeSegmentsProbe";

    /** The route as a collision message names it: the method and the template. */
    public static final String ROUTE_LABEL = "GET " + ROUTE;

    /** The fixed body the resource answers with. */
    public static final String BODY = "three-segments-probe";

    /** Creates the resource. */
    public ThreeSegmentsProbeResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @param a the first segment
     * @param b the second segment
     * @param c the third segment
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String threeSegmentsProbe(@PathParam("a") String a, @PathParam("b") String b, @PathParam("c") String c) {
        return BODY;
    }
}
