// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The IT's later {@code /apidocs/*} JAX-RS mount: declares {@code GET /management/openapi.json}
 * (the same URL the {@code SYSTEM_FIRST} synthetic mount serves), plus {@code GET /other} and
 * {@code GET /fails} as live controls, each counting its own invocations.
 */
@Path("")
public final class LaterApidocsResource {

    private final AtomicInteger managementCount = new AtomicInteger();
    private final AtomicInteger otherCount = new AtomicInteger();
    private final AtomicInteger failsCount = new AtomicInteger();

    /**
     * Declares the same URL the synthetic {@code apidocs:management:json} document answers, so a
     * denial that fell through to this mount would be observable here.
     *
     * @return a fixed body
     */
    @GET
    @Path("/management/openapi.json")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "laterManagement")
    public String management() {
        managementCount.incrementAndGet();
        return "later-management";
    }

    /**
     * A live control proving this mount's resource counter is reachable at all.
     *
     * @return a fixed body
     */
    @GET
    @Path("/other")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "laterOther")
    public String other() {
        otherCount.incrementAndGet();
        return "later-other";
    }

    /**
     * A live control proving this mount's error interceptor is reachable at all.
     *
     * @return never returns
     */
    @GET
    @Path("/fails")
    @Operation(operationId = "laterFails")
    public String fails() {
        failsCount.incrementAndGet();
        throw new RuntimeException("later-fails");
    }

    /**
     * Returns how many times {@link #management()} ran.
     *
     * @return the invocation count
     */
    public int managementCount() {
        return managementCount.get();
    }

    /**
     * Returns how many times {@link #other()} ran.
     *
     * @return the invocation count
     */
    public int otherCount() {
        return otherCount.get();
    }

    /**
     * Returns how many times {@link #fails()} ran.
     *
     * @return the invocation count
     */
    public int failsCount() {
        return failsCount.get();
    }
}
