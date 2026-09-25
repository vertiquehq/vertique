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
 * TP-003 control fixture (T004): the counting resource {@link PublicProbeApplication} lists in
 * {@code getClasses()}, cataloged by this unit's {@code GeneratedJaxRsResourcesModule}. The
 * control case 4 composes successfully, so this resource IS resolved, and
 * {@link #CONSTRUCTIONS} counts that resolution.
 */
@Path("/conflict/publicProbe")
public class PublicProbeResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public PublicProbeResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /conflict/publicProbe}.
     *
     * @return the fixed body {@code "publicProbe"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String publicProbe() {
        return "publicProbe";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
