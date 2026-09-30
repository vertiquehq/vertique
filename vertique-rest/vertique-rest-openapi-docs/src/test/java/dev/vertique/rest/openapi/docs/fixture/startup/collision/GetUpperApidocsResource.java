// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /APIDOCS/{b}/{c}}: a plain route whose literal segment differs
 * from the default prefix only in case. It declares no path parameter, so no binding stands between
 * routing and its answer count.
 */
@Path("/APIDOCS/{b}/{c}")
public class GetUpperApidocsResource extends CaseResource {

    /** The literal body {@link #getUpperApidocs} answers with. */
    public static final String MARKER = "case-get-upper-apidocs";

    /** Creates the resource with no answered request. */
    public GetUpperApidocsResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /APIDOCS/{b}/{c}}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getUpperApidocs() {
        return answer();
    }
}
