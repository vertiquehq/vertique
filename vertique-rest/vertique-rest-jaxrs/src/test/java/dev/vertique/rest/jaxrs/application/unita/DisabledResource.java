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
 * Conditionally-gated JAX-RS resource fixture. It conceptually carries a
 * {@code @ConditionalOnProperty(name = "unita.disabledResource.enabled")} — mirrored at runtime by
 * {@code unita}'s {@link GeneratedJaxRsResourcesModule}'s hand-coded
 * {@code DISABLED_RESOURCE_BINDING_CONDITIONS} array, the same shape T001's legacy
 * {@code DisabledResource} uses, since {@code vertique-codegen-core} (which owns the compile-time
 * annotation) is not a test dependency of this module. No fixture configuration in this task sets
 * {@code unita.disabledResource.enabled}, so the condition never matches by default.
 * {@link #failIfConstructed} lets proofs assert this resource's lazy
 * {@link jakarta.inject.Provider} is never called while its condition fails or it is otherwise
 * unselected.
 */
@Path("/disabled")
public class DisabledResource {

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** When {@code true}, construction throws {@link AssertionError} instead of counting. */
    private static volatile boolean failIfConstructed = false;

    /** Counts the construction, or fails per {@link #failIfConstructed}. */
    @Inject
    public DisabledResource() {
        if (failIfConstructed) {
            throw new AssertionError(DisabledResource.class.getSimpleName() + " must not be constructed");
        }
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Handles {@code GET /disabled}.
     *
     * @return the fixed body {@code "disabled"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String disabled() {
        return "disabled";
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
