// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link GuardedManagementApi}: two operations guarded exactly as the application's
 * protected document is, by {@code @SecurityRequirement(name = "bearerAuth")} and
 * {@code @RolesAllowed("admin")}.
 *
 * <ul>
 *   <li>{@code GET /twin} ({@value #READ_TWIN}) answers a fixed plain-text body;
 *   <li>{@code GET /fail} ({@value #FAIL_TWIN}) always throws, so its failure runs the JAX-RS error
 *       pipeline and every registered error interceptor.
 * </ul>
 */
@Path("/")
public class TwinResource {

    /** The path of {@link #readTwin()}, relative to the application. */
    public static final String TWIN_PATH = "/twin";

    /** The path of {@link #failTwin()}, relative to the application. */
    public static final String FAIL_PATH = "/fail";

    /** The operation id of {@link #readTwin()}. */
    public static final String READ_TWIN = "readTwin";

    /** The operation id of {@link #failTwin()}. */
    public static final String FAIL_TWIN = "failTwin";

    /** The fixed body {@link #readTwin()} returns. */
    public static final String BODY = "twin";

    /** Creates the resource. */
    public TwinResource() {}

    /**
     * Handles {@code GET /twin}.
     *
     * @return the fixed body {@value #BODY}
     */
    @GET
    @Path(TWIN_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = READ_TWIN)
    @SecurityRequirement(name = GuardedManagementApi.SECURITY_SCHEME)
    @RolesAllowed(GuardedManagementApi.ROLE)
    public String readTwin() {
        return BODY;
    }

    /**
     * Handles {@code GET /fail} by throwing.
     *
     * @return never returns
     * @throws IllegalStateException always
     */
    @GET
    @Path(FAIL_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = FAIL_TWIN)
    @SecurityRequirement(name = GuardedManagementApi.SECURITY_SCHEME)
    @RolesAllowed(GuardedManagementApi.ROLE)
    public String failTwin() {
        throw new IllegalStateException("the twin resource failed on purpose");
    }
}
