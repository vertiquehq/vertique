// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /{rest: .*}}: a regex route whose parameter spans {@code /}. It
 * declares no path parameter, so no binding stands between routing and its answer count.
 */
@Path("/{rest: .*}")
public class GetRestResource extends CaseResource {

    /** The literal body {@link #getRest} answers with. */
    public static final String MARKER = "case-get-rest";

    /** Creates the resource with no answered request. */
    public GetRestResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{rest: .*}}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getRest() {
        return answer();
    }
}
