// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /docs/{name}/openapi.json}: on a mount at {@code /api/*} it answers
 * the JSON form of every document under the prefix {@code /api/docs}, but not the YAML form.
 */
@Path("/docs/{name}/openapi.json")
public class DocsNameResource extends CaseResource {

    /** The literal body {@link #getDocsName} answers with. */
    public static final String MARKER = "case-get-docs-name";

    /** Creates the resource with no answered request. */
    public DocsNameResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /docs/{name}/openapi.json}.
     *
     * @param name the document name segment
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getDocsName(@PathParam("name") String name) {
        return answer();
    }
}
