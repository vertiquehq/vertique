// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 conflict fixture (T004): the counting resource {@link GammaApplication} lists in
 * {@code getClasses()}, cataloged by this unit's {@code GeneratedJaxRsResourcesModule}. Because
 * {@link GammaApplication}'s conflicting case fails at composer step 1b, before step 4, this
 * resource's {@link #CONSTRUCTIONS} counter must stay {@code 0} in every case that names it.
 */
@Path("/conflict/gamma")
public class GammaResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public GammaResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /conflict/gamma}.
     *
     * @return the fixed body {@code "gamma"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String gamma() {
        return "gamma";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
