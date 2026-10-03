// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource whose operation id is the synthetic id of the {@code public} document's YAML form. Its
 * one operation, {@code GET /x}, declares the operation id {@value #OPERATION_ID} and answers with
 * {@value #MARKER}.
 */
@Path("/x")
public class ReservedYamlResource extends CountingResource {

    /** The operation id the resource's one operation declares. */
    public static final String OPERATION_ID = "apidocs:public:yaml";

    /** The literal body the resource answers with. */
    public static final String MARKER = "reserved-public-yaml";

    /** Creates the resource with no answered request. */
    public ReservedYamlResource() {
        super(MARKER);
    }

    /**
     * Answers with {@value #MARKER}.
     *
     * @return the marker
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = OPERATION_ID)
    public String reservedPublicYaml() {
        return answer();
    }
}
