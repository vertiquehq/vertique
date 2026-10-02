// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource of the application {@code catalog} whose one operation, {@code GET /reserved}, declares
 * the synthetic operation id of the {@code partner} document's JSON form, {@value #OPERATION_ID}.
 */
@Path(ReservedPartnerJsonResource.ROUTE)
public class ReservedPartnerJsonResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/reserved";

    /** The operation id the resource's one operation declares. */
    public static final String OPERATION_ID = "apidocs:partner:json";

    /** The fixed body the resource answers with. */
    public static final String BODY = "reserved-partner-json";

    /** Creates the resource. */
    public ReservedPartnerJsonResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = OPERATION_ID)
    public String reservedPartnerJson() {
        return BODY;
    }
}
