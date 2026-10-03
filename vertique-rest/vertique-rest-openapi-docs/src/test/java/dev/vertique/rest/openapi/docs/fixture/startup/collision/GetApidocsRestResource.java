// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /apidocs/{rest: .+}}: a regex route whose parameter spans
 * {@code /}, so it answers every path under {@code /apidocs/}.
 */
@Path("/apidocs/{rest: .+}")
public class GetApidocsRestResource extends CaseResource {

    /** The literal body {@link #getApidocsRest} answers with. */
    public static final String MARKER = "case-get-apidocs-rest";

    /** Creates the resource with no answered request. */
    public GetApidocsRestResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /apidocs/{rest: .+}}.
     *
     * @param rest the path after {@code /apidocs/}
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getApidocsRest(@PathParam("rest") String rest) {
        return answer();
    }
}
