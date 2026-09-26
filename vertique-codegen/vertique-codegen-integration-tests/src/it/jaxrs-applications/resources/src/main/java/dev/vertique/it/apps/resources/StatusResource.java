// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.resources;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DI-eligible JAX-RS resource fixture selected by {@code ManagementApplication} (T003 TP-005). The
 * real {@code JaxRsPipelineProcessor} contributes this class to the generated resource catalog
 * because its constructor carries {@code @Inject}.
 */
@Path("/status")
public class StatusResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public StatusResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /status}.
     *
     * @return the fixed body {@code "status"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String status() {
        return "status";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
