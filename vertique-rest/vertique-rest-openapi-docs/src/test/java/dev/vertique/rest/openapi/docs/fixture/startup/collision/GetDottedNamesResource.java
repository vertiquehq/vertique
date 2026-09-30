// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /{a.b}/{c-d}/{e}}: a plain route whose parameter names contain
 * {@code .} and {@code -}, characters the JAX-RS template grammar accepts in a name. It declares no
 * path parameter, so no binding stands between routing and its answer count.
 */
@Path("/{a.b}/{c-d}/{e}")
public class GetDottedNamesResource extends CaseResource {

    /** The literal body {@link #getDottedNames} answers with. */
    public static final String MARKER = "case-get-dotted-names";

    /** Creates the resource with no answered request. */
    public GetDottedNamesResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{a.b}/{c-d}/{e}}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getDottedNames() {
        return answer();
    }
}
