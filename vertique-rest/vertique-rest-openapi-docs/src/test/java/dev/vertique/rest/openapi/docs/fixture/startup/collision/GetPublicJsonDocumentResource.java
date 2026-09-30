// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The case resource of {@code GET /apidocs/public/openapi.json}: a literal route equal to the JSON
 * form of document {@code public} under the default prefix.
 */
@Path("/apidocs/public/openapi.json")
public class GetPublicJsonDocumentResource extends CaseResource {

    /** The literal body {@link #getPublicJsonDocument} answers with. */
    public static final String MARKER = "case-get-public-json-document";

    /** Creates the resource with no answered request. */
    public GetPublicJsonDocumentResource() {
        super(MARKER);
    }

    /**
     * Handles {@code GET /apidocs/public/openapi.json}.
     *
     * @return the marker {@value #MARKER}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getPublicJsonDocument() {
        return answer();
    }
}
