// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The one resource of a hand-built JAX-RS mount placed under the documentation prefix. Its one
 * operation, {@code GET /probe} ({@value #OPERATION_ID}), answers with {@value #BODY}.
 */
@Path(ExtraDocsProbeResource.ROUTE)
public class ExtraDocsProbeResource {

    /** The resource path, relative to its mount. */
    public static final String ROUTE = "/probe";

    /** The operation id of {@link #extraDocsProbe}. */
    public static final String OPERATION_ID = "extraDocsProbe";

    /** The fixed body the resource answers with. */
    public static final String BODY = "extra-docs-probe";

    /** Creates the resource. */
    public ExtraDocsProbeResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String extraDocsProbe() {
        return BODY;
    }
}
