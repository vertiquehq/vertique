// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /{a}/public.openapi.json}: a plain route whose literal last
 * segment holds dots where a document URL has {@code /}. Vert.x matches each dot literally, so the
 * route never answers {@code /apidocs/public/openapi.json}. It declares no path parameter, so no
 * binding stands between routing and its answer count.
 */
@Path("/{a}/public.openapi.json")
public class GetDottedDocumentNameResource extends CaseResource {

    /** The literal body {@link #getDottedDocumentName} answers with. */
    public static final String MARKER = "case-get-dotted-document-name";

    /** Creates the resource with no answered request. */
    public GetDottedDocumentNameResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /{a}/public.openapi.json}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getDottedDocumentName() {
        return answer();
    }
}
