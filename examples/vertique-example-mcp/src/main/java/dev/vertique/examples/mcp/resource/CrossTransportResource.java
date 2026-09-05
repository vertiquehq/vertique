// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resource;

import dev.vertique.ratelimit.aop.RateLimited;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** REST endpoints used to prove the MCP example consumes the shared rate-limit runtime. */
@Path("/cross-transport")
@Produces(MediaType.APPLICATION_JSON)
public class CrossTransportResource {

    @Inject
    public CrossTransportResource() {}

    @GET
    @Path("/principal")
    @RolesAllowed("user")
    @SecurityRequirement(name = "bearerAuth")
    @RateLimited(
            policy = "mcp-shared",
            subject = RateLimitSubject.EFFECTIVE_PRINCIPAL,
            anonymous = AnonymousRateLimitPolicy.SHARED_BUCKET)
    public Future<JsonObject> principal() {
        return Future.succeededFuture(new JsonObject().put("transport", "rest").put("accepted", true));
    }

    @GET
    @Path("/observed")
    @RolesAllowed("user")
    @SecurityRequirement(name = "bearerAuth")
    @RateLimited(
            policy = "mcp-observed",
            subject = RateLimitSubject.EFFECTIVE_PRINCIPAL,
            anonymous = AnonymousRateLimitPolicy.SHARED_BUCKET)
    public Future<JsonObject> observed() {
        return Future.succeededFuture(new JsonObject().put("transport", "rest").put("accepted", true));
    }

    @GET
    @Path("/ip")
    @RateLimited(policy = "mcp-ip", subject = RateLimitSubject.IP, anonymous = AnonymousRateLimitPolicy.SHARED_BUCKET)
    public Future<JsonObject> ip() {
        return Future.succeededFuture(new JsonObject().put("transport", "rest").put("accepted", true));
    }
}
