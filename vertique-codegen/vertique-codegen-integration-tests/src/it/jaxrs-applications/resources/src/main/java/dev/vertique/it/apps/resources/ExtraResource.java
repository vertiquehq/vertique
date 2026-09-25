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
 * DI-eligible JAX-RS resource fixture never listed by any {@code Application} (T003 TP-005): it is
 * cataloged, but no active application's {@code getClasses()} selects it, so its lazy
 * {@link jakarta.inject.Provider} must never be called and {@link #CONSTRUCTIONS} must stay {@code 0}.
 */
@Path("/extra")
public class ExtraResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Counts the construction. */
    @Inject
    public ExtraResource() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /extra}.
     *
     * @return the fixed body {@code "extra"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String extra() {
        return "extra";
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
