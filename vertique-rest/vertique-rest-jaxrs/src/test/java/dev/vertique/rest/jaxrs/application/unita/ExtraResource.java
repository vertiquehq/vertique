// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unconditional JAX-RS resource fixture, contributed by {@code unita}'s
 * {@link GeneratedJaxRsResourcesModule#extraResourceBinding} and cataloged by
 * {@link GeneratedJaxRsResourcesModule#extraResourceEntry}. {@link #failIfConstructed} lets
 * laziness proofs (TP-003) assert this resource's lazy {@link jakarta.inject.Provider} is never
 * called when it is not selected by any active application.
 */
@Path("/extra")
public class ExtraResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** When {@code true}, construction throws {@link AssertionError} instead of counting. */
    private static volatile boolean failIfConstructed = false;

    /** Counts the construction, or fails per {@link #failIfConstructed}. */
    @Inject
    public ExtraResource() {
        if (failIfConstructed) {
            throw new AssertionError(ExtraResource.class.getSimpleName() + " must not be constructed");
        }
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

    /**
     * Switches whether the next construction throws {@link AssertionError}.
     *
     * @param fail {@code true} to fail on construction instead of counting it
     */
    public static void setFailIfConstructed(boolean fail) {
        failIfConstructed = fail;
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0} and disables {@link #failIfConstructed}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
        failIfConstructed = false;
    }
}
