// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /{a}/{b}/openapi.{ext}}: a plain route with a parameter inside
 * its last segment, after literal text. It declares no path parameter, so no binding stands between
 * routing and its answer count.
 */
@Path("/{a}/{b}/openapi.{ext}")
public class GetOpenapiExtensionResource extends CaseResource {

    /** The literal body {@link #getOpenapiExtension} answers with. */
    public static final String MARKER = "case-get-openapi-extension";

    /** Creates the resource with no answered request. */
    public GetOpenapiExtensionResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{a}/{b}/openapi.{ext}}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getOpenapiExtension() {
        return answer();
    }
}
