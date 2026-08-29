// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.resource;

import dev.vertique.examples.services.service.CacheProbeResult;
import dev.vertique.examples.services.service.CacheProbeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST resource exposing secured and public cache-probe calls through the generated service client. */
@Path("/cache-probe")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Cache probe", description = "Identity-scoped service cache demonstration endpoints")
public class CacheProbeResource {

    private final CacheProbeService cacheProbeService;

    /**
     * Creates a cache-probe resource.
     *
     * @param cacheProbeService the generated typed client for the cache-probe service
     */
    @Inject
    public CacheProbeResource(CacheProbeService cacheProbeService) {
        this.cacheProbeService = cacheProbeService;
    }

    /** Invokes the cache probe as an authenticated caller. */
    @GET
    @Path("/secured")
    @RolesAllowed("user")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "cacheProbeSecured", summary = "Invoke the secured cache probe")
    @ApiResponse(responseCode = "200", description = "Cache probe result returned")
    public Future<CacheProbeResult> securedProbe() {
        return cacheProbeService.probe();
    }

    /** Invokes the cache probe without authentication. */
    @GET
    @Path("/public")
    @PermitAll
    @Operation(operationId = "cacheProbePublic", summary = "Invoke the public cache probe")
    @ApiResponse(responseCode = "200", description = "Cache probe result returned")
    public Future<CacheProbeResult> publicProbe() {
        return cacheProbeService.probe();
    }
}
